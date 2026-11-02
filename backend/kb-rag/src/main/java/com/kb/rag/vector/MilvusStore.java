package com.kb.rag.vector;

import com.kb.rag.ai.AiClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Milvus 向量存储实现（M6）：RESTful v2 API（/v2/vectordb/*），无官方 Java SDK 依赖。
 *
 * <h3>为什么走 REST 而不是 langchain4j-milvus / milvus-sdk-java</h3>
 * ① SDK(grpc) 会把 protobuf/grpc-netty 一大棵依赖树带进 fat-jar，与现有极简
 *    "手写 HttpClient" 的密钥收敛风格（AiClientFactory 同款）不一致；
 * ② RESTful v2 自 Milvus 2.4 起 GA，upsert/search/delete/count 四个动词全覆盖本项目需要；
 * ③ 排错直观（curl 可复现每个调用），答辩/接手友好。
 * 生产级高并发建议换官方 SDK——接口不变（VectorStore），只替换本类实现。
 *
 * <h3>索引与度量</h3>
 * FLOAT_VECTOR(dim=1024) + HNSW/IP：与 pgvector 侧语义完全对齐（内积=余弦，
 * embedding 已归一化），迁移/双跑时分數口径一致，A/B 才有可比性。
 *
 * <h3>惰性建集合</h3>
 * 首写时 ensureCollection（幂等探测 has/list），避免启动强依赖 Milvus 可用——
 * 与 pgvector 的"compose 初始化 SQL"差异在于 Milvus 无 initdb 挂载点。
 */
@Slf4j
@Component("milvusStore")
public class MilvusStore implements VectorStore {

    private static final String COLLECTION = "kb_vectors";

    private final AiClientFactory ai;
    private final String base;
    private final int dim;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile boolean collectionReady = false;

    public MilvusStore(AiClientFactory ai,
                       @Value("${kb.vector.milvus-url}") String milvusUrl,
                       @Value("${kb.ai.embedding-dim}") int dim) {
        this.ai = ai;
        this.base = milvusUrl.replaceAll("/+$", "");
        this.dim = dim;
    }

    @Override
    public String name() { return "milvus"; }

    @Override
    public int upsert(List<ChunkRow> rows) {
        if (rows.isEmpty()) return 0;
        ensureCollection();
        List<float[]> vecs = ai.embed(rows.stream().map(ChunkRow::content).toList());
        // 分批防请求体超限（Milvus REST 单请求默认上限较宽，但 1024 维 * 大批次仍应分段）
        int n = 0;
        for (int i = 0; i < rows.size(); i += 64) {
            List<Map<String, Object>> data = new ArrayList<>();
            for (int j = i; j < Math.min(i + 64, rows.size()); j++) {
                ChunkRow r = rows.get(j);
                data.add(Map.of("id", r.chunkId(), "kb_id", r.kbId(), "doc_id", r.docId(),
                        "content", cut(r.content()), "vector", vecs.get(j)));
            }
            var resp = post("/v2/vectordb/entities/upsert",
                    Map.of("collectionName", COLLECTION, "data", data));
            Map<String, Object> d = (Map<String, Object>) resp.getOrDefault("data", Map.of());
            n += asInt(d.getOrDefault("upsertCount", data.size()));
        }
        return n;
    }

    @Override
    public void deleteByDocIds(Collection<Long> docIds) {
        if (docIds == null || docIds.isEmpty()) return;
        ensureCollection();
        String in = docIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        post("/v2/vectordb/entities/delete",
                Map.of("collectionName", COLLECTION, "filter", "doc_id in [" + in + "]"));
    }

    @Override
    public void deleteByChunkIds(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return;
        ensureCollection();
        String in = chunkIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        post("/v2/vectordb/entities/delete",
                Map.of("collectionName", COLLECTION, "filter", "id in [" + in + "]"));
    }

    public void deleteByKbIds(Collection<Long> kbIds) {
        if (kbIds.isEmpty()) return;
        ensureCollection();
        String in = kbIds.stream().map(String::valueOf).reduce("", (a, b) -> a.isEmpty() ? b : a + "," + b);
        post("/v2/vectordb/entities/delete",
                Map.of("collectionName", COLLECTION, "filter", "kb_id in [" + in + "]"));
    }

    @Override
    public List<Hit> search(String query, Collection<Long> kbIds, int topK, double minScore) {
        if (kbIds.isEmpty()) return List.of();
        ensureCollection();
        float[] qv = ai.embed(List.of(query)).get(0);
        String in = kbIds.stream().map(String::valueOf).reduce("", (a, b) -> a.isEmpty() ? b : a + "," + b);
        var resp = post("/v2/vectordb/entities/search", Map.of(
                "collectionName", COLLECTION,
                "annsField", "vector",
                "data", List.of(qv),
                "limit", topK,
                "filter", "kb_id in [" + in + "]",
                "outputFields", List.of("id", "doc_id", "kb_id", "content")));
        List<Map<String, Object>> arr = (List<Map<String, Object>>) resp.get("data");
        List<Hit> out = new ArrayList<>();
        if (arr != null) {
            for (var d : arr) {
                double score = ((Number) d.getOrDefault("distance", 0.0)).doubleValue(); // IP: 越大越近
                if (score < minScore) continue;
                out.add(new Hit(asLong(d.get("id")), asLong(d.get("doc_id")), asLong(d.get("kb_id")),
                        String.valueOf(d.getOrDefault("content", "")), score));
            }
        }
        return out;
    }

    /**
     * 该库向量条数。注意：v2.3.16 REST 的 count(*) 被服务端拒绝
     * （"count entities with pagination is not allowed"，实测），退而求其次
     * 用 query 拉 id 列表计数——仅后台诊断低频调用，可接受；
     * 若切生产级统计请走官方 SDK 的 QueryIterator。
     */
    @Override
    public long countByKb(Long kbId) {
        ensureCollection();
        var resp = post("/v2/vectordb/entities/query", Map.of(
                "collectionName", COLLECTION,
                "filter", "kb_id == " + kbId,
                "outputFields", List.of("id"),
                "limit", 16384));
        List<Map<String, Object>> arr = (List<Map<String, Object>>) resp.get("data");
        return arr == null ? 0 : arr.size();
    }

    // ---------- internal ----------

    /** 惰性建集合：has -> list 名称匹配；不存在则 create（schema 一次定稿：HNSW/IP）。 */
    private void ensureCollection() {
        if (collectionReady) return;
        synchronized (this) {
            if (collectionReady) return;
            var exists = post("/v2/vectordb/collections/list", Map.of());
            List<Object> names = (List<Object>) exists.getOrDefault("data", List.of());
            if (!names.contains(COLLECTION)) {
                post("/v2/vectordb/collections/create", Map.of(
                        "collectionName", COLLECTION,
                        "schema", Map.of(
                                "autoID", false,
                                "enableDynamicField", true,
                                "fields", List.of(
                                        Map.of("fieldName", "id", "dataType", "Int64", "isPrimary", true),
                                        Map.of("fieldName", "kb_id", "dataType", "Int64"),
                                        Map.of("fieldName", "doc_id", "dataType", "Int64"),
                                        Map.of("fieldName", "content", "dataType", "VarChar",
                                                "elementTypeParams", Map.of("max_length", "8192")),
                                        Map.of("fieldName", "vector", "dataType", "FloatVector",
                                                "elementTypeParams", Map.of("dim", dim)))),
                        // 实测 v2.3.16 RESTful：create 时显式 HNSW+params 报
                        // "only metric type can be passed when use AutoIndex"（RESTful v2 在该版
                        // 强制走 AUTOINDEX 语义）——AUTOINDEX + metricType=IP 是验证过的可用形态；
                        // 度量与 pgvector 侧一致(内积=余弦，向量已归一化)，索引类型由 Milvus 自选。
                        "indexParams", List.of(Map.of(
                                "fieldName", "vector", "indexName", "vec_idx",
                                "metricType", "IP", "indexType", "AUTOINDEX"))));
                log.info("milvus collection {} created (HNSW/IP dim={})", COLLECTION, dim);
            }
            collectionReady = true;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> body) {
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400)
                throw new IllegalStateException("milvus " + path + " status=" + resp.statusCode());
            Map<String, Object> m = new com.fasterxml.jackson.databind.ObjectMapper().readValue(resp.body(), Map.class);
            // RESTful v2 实测（v2.3.16）：成功 code=200（不是 0！），业务数据在 data 字段
            int code = asInt(m.getOrDefault("code", 200));
            if (code != 200) throw new IllegalStateException("milvus " + path + " code=" + code + " msg=" + m.get("message"));
            return m;
        } catch (IllegalStateException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("milvus io: " + e.getClass().getSimpleName(), e); }
    }

    private static String cut(String s) { return s.length() > 8000 ? s.substring(0, 8000) : s; }
    private static int asInt(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static long asLong(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
}
