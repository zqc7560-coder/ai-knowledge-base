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
    private final String visionModel;
    private final HttpClient http;

    public AiClientFactory(
            @Value("${kb.ai.api-key}") String apiKey,
            @Value("${kb.ai.base-url}") String baseUrl,
            @Value("${kb.ai.rerank-url}") String rerankUrl,
            @Value("${kb.ai.chat-model}") String chatModel,
            @Value("${kb.ai.embedding-model}") String embeddingModel,
            @Value("${kb.ai.embedding-dim}") int embeddingDim,
            @Value("${kb.ai.rerank-model}") String rerankModel,
            @Value("${kb.ai.vision-model:qwen-vl-ocr}") String visionModel) {
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
        this.visionModel = visionModel;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        log.info("AiClientFactory init models=[chat:{}, emb:{}, dim:{}, rerank:{}, vision:{}] keyMask={}",
                chatModel, embeddingModel, embeddingDim, rerankModel, visionModel, mask(apiKey));
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

    /**
     * 同步 chat：非流式场景专用（查询改写、标题生成这类"要结果不要打字机"的调用）。
     *
     * <h3>为什么手写 HttpClient 而不用 langchain4j 的 ChatModel</h3>
     * ① 密钥收敛：手写层让"全工程只有本类能拼 Authorization 头"这条红线可被静态审计
     *    （grep Bearer 只命中 AiClientFactory 一处）；② 完全掌控超时/流式解析/重试节奏，
     *    避免封装层吞掉 429 细节导致降级策略失真；③ 少一层运行时依赖，换上游模型商只改本文件。
     *
     * <h3>失败语义</h3>
     * 所有异常统一包装成 AiUpstreamException（message 只含业务文案）——
     * 上游 4xx/5xx 的原始 body 可能回显请求头（含 Bearer），绝不允许进日志或透出给前端，
     * 这是密钥规范 L6"防回声"的实现点。
     */
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

    /**
     * 流式 chat（OpenAI 协议 stream=true）：每收到一个 delta token 回调 sink；
     * 返回聚合后的完整文本。上游异常直接抛（调用方发 SSE error 事件）。
     */
    @SuppressWarnings("unchecked")
    public String chatStream(List<Map<String, String>> messages, java.util.function.Consumer<String> sink) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("model", chatModel);
        body.put("messages", messages);
        body.put("stream", true);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(180))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(toJson(body)))
                    .build();
            HttpResponse<java.util.stream.Stream<String>> resp =
                    http.send(req, HttpResponse.BodyHandlers.ofLines());
            if (resp.statusCode() >= 400) {
                log.warn("upstream chat stream status={}", resp.statusCode());
                throw new AiUpstreamException("AI 服务暂时不可用(" + resp.statusCode() + ")", resp.statusCode() == 429, null);
            }
            StringBuilder full = new StringBuilder();
            resp.body().forEach(line -> {
                if (!line.startsWith("data:")) return;
                String payload = line.substring(5).trim();
                if (payload.equals("[DONE]") || payload.isEmpty()) return;
                try {
                    Map<String, Object> chunk = fromJson(payload);
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                    if (choices == null || choices.isEmpty()) return;
                    Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                    Object c = delta == null ? null : delta.get("content");
                    if (c != null && !c.toString().isEmpty()) {
                        full.append(c);
                        sink.accept(c.toString());
                    }
                } catch (Exception ignore) { /* 坏行跳过 */ }
            });
            return full.toString();
        } catch (AiUpstreamException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiUpstreamException("AI 调用被中断", false, e);
        } catch (Exception e) {
            log.warn("chatStream io-fail {}", e.getClass().getSimpleName());
            throw new AiUpstreamException("AI 网络异常", false, e);
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

    /**
     * 视觉模型单图问答（M6 OCR 用）。
     * <h3>为什么单独开这个方法</h3>
     * OCR 走的是【多模态消息】——content 不是字符串而是 [image_url, text] 数组，
     * 与现有纯文本 chat 的 body 结构不同，混用会让泛型签名变复杂；单图单问的 OCR
     * 场景独立成方法最清晰，且只有 OcrService 一个调用方（复用 AiUpstreamException 语义）。
     *
     * 模型可配（kb.ai.vision-model，默认 qwen-vl-ocr——实测该 key 可用的唯一 VL 模型，
     * 中文识别准确；qwen-vl-max/plus 此 key 未开通，404）。
     *
     * 密钥安全：同其它方法，Bearer 仅在 post() 内拼接，报文不回显（L4/L6）。
     */
    public String visionOcr(byte[] imageBytes, String instruction) {
        String dataUrl = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(imageBytes);
        java.util.List<Map<String, Object>> content = java.util.List.of(
                Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)),
                Map.of("type", "text", "text", instruction));
        Map<String, Object> body = Map.of(
                "model", visionModel,
                "messages", java.util.List.of(Map.of("role", "user", "content", content)),
                "max_tokens", 2000);
        String resp = post(baseUrl + "/chat/completions", toJson(body), Duration.ofSeconds(120));
        Map<String, Object> parsed = fromJson(resp);
        List<Map<String, Object>> choices = (List<Map<String, Object>>) parsed.get("choices");
        if (choices == null || choices.isEmpty()) throw new AiUpstreamException("OCR 返回空", false, null);
        Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
        return String.valueOf(msg.get("content"));
    }

    // ---------- infra ----------

    private String post(String url, String jsonBody, Duration timeout) {
        // 连接级重试：本部署环境（Docker Desktop/WSL2 NAT）对外 HTTPS 间歇性 RST——
        // ConnectException 意味着请求尚未发出，重试无重复计费风险；429 交由上层退避。
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return doPost(url, jsonBody, timeout);
            } catch (AiUpstreamException biz) {
                throw biz;                      // 上游明确响应（429/4xx/5xx）不重连重试，语义交上层
            } catch (Exception io) {            // 仅连接/IO 层异常重试
                last = io;
                if (attempt < 3) sleepBackoff(attempt);
            }
        }
        log.warn("upstream {} io-fail after 3 tries: {}", shortPath(url), last == null ? "?" : last.getClass().getSimpleName());
        throw new AiUpstreamException("AI 网络异常", false, last);
    }

    private String doPost(String url, String jsonBody, Duration timeout) throws Exception {
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
