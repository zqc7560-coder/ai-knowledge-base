package com.kb.rag.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * AI 上游客户端工厂 —— 全工程唯一读取 AI_API_KEY 的地方（密钥安全规范 L4）。
 * 其他模块一律通过本类的公开方法使用 AI 能力，禁止自行读环境变量。
 *
 * 上游：阿里云百炼 DashScope OpenAI 兼容端点。
 *  - chat      POST {base}/chat/completions
 *  - embedding POST {base}/embeddings
 *  - rerank    POST 原生 rerank 端点（gte-rerank-v2，非 OpenAI 协议）
 *
 * 注意：本类任何日志/异常消息均不得包含 key；toString 已固化脱敏。
 */
@Slf4j
@Component
public class AiClientFactory {

    private final String apiKey;
    private final String baseUrl;
    private final String rerankUrl;
    private final String chatModel;
    private final String embeddingModel;
    private final int embeddingDim;
    private final String rerankModel;
    private final HttpClient http;

    public AiClientFactory(
            @Value("${kb.ai.api-key}") String apiKey,
            @Value("${kb.ai.base-url}") String baseUrl,
            @Value("${kb.ai.rerank-url}") String rerankUrl,
            @Value("${kb.ai.chat-model}") String chatModel,
            @Value("${kb.ai.embedding-model}") String embeddingModel,
            @Value("${kb.ai.embedding-dim}") int embeddingDim,
            @Value("${kb.ai.rerank-model}") String rerankModel) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("kb.ai.api-key is not configured (AI_API_KEY env)");
        }
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.rerankUrl = rerankUrl;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.embeddingDim = embeddingDim;
        this.rerankModel = rerankModel;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        log.info("AiClientFactory init models=[chat:{}, emb:{}, dim:{}, rerank:{}] keyMask={}",
                chatModel, embeddingModel, embeddingDim, rerankModel, mask(apiKey));
    }

    public static String mask(String key) {
        if (key == null || key.length() < 8) return "****";
        return key.substring(0, 5) + "-****" + key.substring(key.length() - 4);
    }

    /** key 尾4位仅允许出现在 admin 配置页（L5）。 */
    public String maskedKey() { return mask(apiKey); }

    public String chatModel() { return chatModel; }
    public String embeddingModel() { return embeddingModel; }
    public int embeddingDim() { return embeddingDim; }

    /** chat 同步调用：返回 assistant 文本。异常统一转 AiUpstreamException（不透传上游报文）。 */
    public String chat(List<Map<String, String>> messages, Double temperature, Integer maxTokens) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("model", chatModel);
        body.put("messages", messages);
        if (temperature != null) body.put("temperature", temperature);
        if (maxTokens != null) body.put("max_tokens", maxTokens);
        try {
            String resp = post(baseUrl + "/chat/completions", toJson(body), Duration.ofSeconds(120));
            Map<String, Object> parsed = fromJson(resp);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) parsed.get("choices");
            if (choices == null || choices.isEmpty()) throw new IllegalStateException("empty choices");
            Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
            return String.valueOf(msg.get("content"));
        } catch (AiUpstreamException e) {
            throw e;
        } catch (Exception e) {
            log.warn("chat call failed reqId={} err={}", com.kb.common.api.RequestIds.current(), e.getClass().getSimpleName());
            throw new AiUpstreamException("AI 服务暂时不可用", e);
        }
    }

    /** 批量向量化：input 列表 -> 每个文本的向量。上游批次上限 10。 */
    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < texts.size(); i += 10) {
            List<String> batch = texts.subList(i, Math.min(i + 10, texts.size()));
            out.addAll(embedBatch(batch));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<float[]> embedBatch(List<String> batch) {
        Map<String, Object> body = Map.of(
                "model", embeddingModel,
                "input", batch,
                "dimensions", embeddingDim,
                "encoding_format", "float");
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String resp = post(baseUrl + "/embeddings", toJson(body), Duration.ofSeconds(60));
                Map<String, Object> parsed = fromJson(resp);
                List<Map<String, Object>> data = (List<Map<String, Object>>) parsed.get("data");
                List<float[]> vecs = new java.util.ArrayList<>();
                for (Map<String, Object> d : data) vecs.add(toFloatArray((List<Number>) d.get("embedding")));
                return vecs;
            } catch (AiUpstreamException e) {
                if (e.busy() && attempt < 3) { sleepBackoff(attempt); continue; }
                throw e;
            }
        }
        throw new AiUpstreamException("embedding 重试耗尽", null);
    }

    /** rerank：query+docs -> 按相关度降序的 (index, score)。失败抛异常由上层降级。 */
    @SuppressWarnings("unchecked")
    public List<RerankItem> rerank(String query, List<String> documents, int topN) {
        Map<String, Object> body = Map.of(
                "model", rerankModel,
                "input", Map.of("query", query, "documents", documents),
                "parameters", Map.of("return_documents", false, "top_n", topN));
        String resp = post(rerankUrl, toJson(body), Duration.ofSeconds(30));
        Map<String, Object> parsed = fromJson(resp);
        Map<String, Object> output = (Map<String, Object>) parsed.get("output");
        List<Map<String, Object>> results = (List<Map<String, Object>>) output.get("results");
        List<RerankItem> items = new java.util.ArrayList<>();
        for (Map<String, Object> r : results) {
            items.add(new RerankItem(((Number) r.get("index")).intValue(),
                    ((Number) r.get("relevance_score")).doubleValue()));
        }
        items.sort((a, b) -> Double.compare(b.score(), a.score()));
        return items;
    }

    public record RerankItem(int index, double score) {}

    // ---------- infra ----------

    private String post(String url, String jsonBody, Duration timeout) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int sc = resp.statusCode();
            if (sc == 429) throw new AiUpstreamException("AI_UPSTREAM_BUSY", true, null);
            if (sc >= 400) {
                // 上游报文可能含请求头回声，一律不落日志（L6）
                log.warn("upstream {} status={}", shortPath(url), sc);
                throw new AiUpstreamException("AI 服务暂时不可用(" + sc + ")", false, null);
            }
            return resp.body();
        } catch (AiUpstreamException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiUpstreamException("AI 调用被中断", false, e);
        } catch (Exception e) {
            log.warn("upstream {} io-fail {}", shortPath(url), e.getClass().getSimpleName());
            throw new AiUpstreamException("AI 网络异常", false, e);
        }
    }

    /** 只留路径尾段，保证 host/带参 URL 不进日志。 */
    private static String shortPath(String url) {
        int idx = url.indexOf("/v1/");
        return idx >= 0 ? url.substring(idx) : url.substring(url.lastIndexOf('/') + 1);
    }

    private static float[] toFloatArray(List<Number> nums) {
        float[] v = new float[nums.size()];
        for (int i = 0; i < nums.size(); i++) v[i] = nums.get(i).floatValue();
        return v;
    }

    private static void sleepBackoff(int attempt) {
        try { Thread.sleep(1000L * attempt * attempt); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String toJson(Object o) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o); }
        catch (Exception e) { throw new IllegalStateException("json serialize", e); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fromJson(String s) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(s, Map.class); }
        catch (Exception e) { throw new AiUpstreamException("AI 响应解析失败", false, e); }
    }

    @Override
    public String toString() {
        return "AiClientFactory{models, keyMask=" + mask(apiKey) + "}";
    }
}
