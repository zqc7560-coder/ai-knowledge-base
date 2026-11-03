package com.kb.rag.vector;

import com.kb.rag.ai.AiClientFactory;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.QueryResults;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.*;
import io.milvus.param.partition.*;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.QueryResultsWrapper;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Milvus 官方 gRPC SDK 实现（M12.1），替代 M6 的手写 REST 适配。
 *
 * <h3>为什么值得引入 SDK 依赖树（推翻 M6 的 REST 决策）</h3>
 * M6 用 REST 是为了零依赖 + 可 curl 排错，达成演示目标；但它留下三处实测妥协：
 * ① v2.3 REST 强制 AUTOINDEX（HNSW 参数不可控）；② count(*) 被拒（"pagination not allowed"，
 * 只能 query 拉满 16384 条数数）；③ REST 错误语义靠 code!=200 猜。SDK 全部消解：
 * 建 HNSW 索引显式参数、count 走 query iterator、错误是类型化 R&lt;T&gt;。
 * 代价=grpc/protobuf 依赖树（fat jar 83MB→约100MB），简历项目可接受。
 *
 * <h3>按库分区（partition by kb_id）</h3>
 * 每个知识库一个分区（kb_&lt;id&gt;），检索只扫相关分区（partitionNames），删库=drop 分区（O(1) 而非
 * filter 全扫）。分区名从 chunkId 反查不到 kb，故分区管理以调用方传入的 kbId 为准：
 * upsert 按 rows 内 kb_id 分组、逐分区 ensure+insert；deleteByKbIds 直接 dropPartitions。
 * <b>为什么不用 partition_key(2.3+)而用物理分区</b>：本系统库数量级是十~百，物理分区上限 4096 足够，
 * 换来 drop=删库零成本；库数上万才需要 partition_key（键哈希到 M 个共享分区）。
 *
 * <h3>连接形态</h3>
 * host:port 由 kb.vector.milvus-url 解析（沿用 REST 时代的配置键，切换实现不改 .env）；
 * 客户端常驻+重连由 grpc 自身处理；@PreDestroy 关闭通道。集合 schema 与 REST 版一致
 * （id/kb_id/doc_id/content/vector），双实现可互查同一份数据——迁移工具语义不变。
 */
@Slf4j
@Component("milvusSdkStore")
public class MilvusSdkStore implements VectorStore {

    private static final String COLLECTION = "kb_vectors";
    private static final String F_ID = "id", F_KB = "kb_id", F_DOC = "doc_id", F_CONTENT = "content", F_VECTOR = "vector";

    private final AiClientFactory ai;
    private final int dim;
    private final String host;
    private final int port;
    /**
     * 惰性客户端（M12.1 回归修复）：构造函数【绝不】建连接。
     * 事故复盘：SDK 客户端构造会同步握 gRPC（连不上则 10s DEADLINE_EXCEEDED 抛异常），
     * 而 Milvus 是可选组件（start-all.bat 的 run-infra 只起 mysql/redis/pgvector）——
     * 容器重启后 Milvus 未起时，eager 连接让 Spring 上下文直接崩，"pgvector 默认用户"无辜躺枪。
     * VectorStoreRouter 注释的设计原则是"未用的那个不碰网络"，REST 版守住了、SDK 版被我破坏，现归还。
     * 代价：真正用 milvus 时首调用多一次建连（一次性，可忽略）；失败在用法处显式抛出，语义更诚实。
     */
    private volatile MilvusServiceClient client;
    private volatile boolean collectionReady = false;

    public MilvusSdkStore(AiClientFactory ai,
                          @Value("${kb.vector.milvus-url}") String milvusUrl,
                          @Value("${kb.ai.embedding-dim}") int dim) {
        this.ai = ai;
        this.dim = dim;
        // milvus-url 形如 http://127.0.0.1:19530 → SDK 要 host+port
        var uri = URI_create(milvusUrl);
        this.host = uri.getHost();
        this.port = uri.getPort();
    }

    /** 双检锁建连；Milvus 不可达时在这里（实际使用点）抛错，而非启动期。 */
    private MilvusServiceClient client() {
        MilvusServiceClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    c = new MilvusServiceClient(ConnectParam.newBuilder()
                            .withHost(host).withPort(port)
                            .withConnectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                            .withRpcDeadline(60, java.util.concurrent.TimeUnit.SECONDS)
                            .build());
                    client = c;
                }
            }
        }
        return c;
    }

    private static java.net.URI URI_create(String url) {
        String s = url.replaceAll("^http(s)?://", "").replaceAll("/+$", "");
        String[] hp = s.split(":");
        return java.net.URI.create("http://" + hp[0] + ":" + (hp.length > 1 ? hp[1] : "19530"));
    }

    @Override public String name() { return "milvus"; }

    @Override
    public int upsert(List<ChunkRow> rows) {
        if (rows.isEmpty()) return 0;
        ensureCollection();
        List<float[]> vecs = ai.embed(rows.stream().map(ChunkRow::content).toList());
        // 按 kb 分组：每库一分区
        Map<Long, List<Integer>> byKb = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) byKb.computeIfAbsent(rows.get(i).kbId(), k -> new ArrayList<>()).add(i);
        int n = 0;
        for (var e : byKb.entrySet()) {
            String part = partitionName(e.getKey());
            ensurePartition(e.getKey());
            List<Long> ids = new ArrayList<>(), docIds = new ArrayList<>(), kbIds = new ArrayList<>();
            List<String> contents = new ArrayList<>();
            List<List<Float>> vectors = new ArrayList<>();
            for (int i : e.getValue()) {
                ChunkRow r = rows.get(i);
                ids.add(r.chunkId()); kbIds.add(r.kbId()); docIds.add(r.docId());
                contents.add(cut(r.content()));
                vectors.add(toList(vecs.get(i)));
            }
            List<InsertParam.Field> fields = List.of(
                    new InsertParam.Field(F_ID, ids), new InsertParam.Field(F_KB, kbIds),
                    new InsertParam.Field(F_DOC, docIds), new InsertParam.Field(F_CONTENT, contents),
                    new InsertParam.Field(F_VECTOR, vectors));
            R<MutationResult> r = client().insert(InsertParam.newBuilder()
                    .withCollectionName(COLLECTION).withPartitionName(part)
                    .withFields(fields).build());
            if (r.getException() != null) throw new IllegalStateException("milvus insert: " + r.getException().getMessage());
            n += (int) r.getData().getInsertCnt();
        }
        return n;
    }

    @Override
    public void deleteByDocIds(Collection<Long> docIds) {
        if (docIds.isEmpty()) return;
        ensureCollection();
        String in = docIds.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
        deleteWhere(COLLECTION, null, F_DOC + " in " + in);
    }

    @Override
    public void deleteByChunkIds(Collection<Long> chunkIds) {
        if (chunkIds.isEmpty()) return;
        ensureCollection();
        String in = chunkIds.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
        deleteWhere(COLLECTION, null, F_ID + " in " + in);
    }

    /** 删库=drop 分区（M12.1 核心收益：O(1)，不扫行）。 */
    @Override
    public void deleteByKbIds(Collection<Long> kbIds) {
        if (kbIds.isEmpty()) return;
        ensureCollection();
        for (Long kbId : kbIds) {
            String part = partitionName(kbId);
            R<Boolean> has = client().hasPartition(io.milvus.param.partition.HasPartitionParam.newBuilder()
                    .withCollectionName(COLLECTION).withPartitionName(part).build());
            if (!Boolean.TRUE.equals(has.getData())) continue;   // 无分区=本就无数据
            // 实测 Milvus 2.3：loaded 分区不许 drop（"please release it first"）——先 release 再 drop。
            // releasePartitions 是分区级，其余库的分区仍在 loaded，检索不受影响。
            client().releasePartitions(ReleasePartitionsParam.newBuilder()
                    .withCollectionName(COLLECTION).withPartitionNames(List.of(part)).build());
            R<?> resp = client().dropPartition(DropPartitionParam.newBuilder()
                    .withCollectionName(COLLECTION).withPartitionName(part).build());
            if (resp.getException() != null) {
                // 兜底：drop 失败退 filter 删行（行清零；空分区壳残留可接受，重建索引会覆盖）
                log.warn("dropPartition kb_{} failed({}), fallback deleteExpr", kbId, resp.getException().getMessage());
                deleteWhere(COLLECTION, part, F_KB + " == " + kbId);
            }
        }
    }

    @Override
    public List<Hit> search(String query, Collection<Long> kbIds, int topK, double minScore) {
        if (kbIds.isEmpty()) return List.of();
        ensureCollection();
        // 只扫相关库的分区；从未写入过的库（分区不存在）跳过——传不存在的分区名 SDK 会报错
        List<String> parts = new ArrayList<>();
        for (Long kbId : kbIds) {
            String part = partitionName(kbId);
            R<Boolean> has = client().hasPartition(io.milvus.param.partition.HasPartitionParam.newBuilder()
                    .withCollectionName(COLLECTION).withPartitionName(part).build());
            if (Boolean.TRUE.equals(has.getData())) parts.add(part);
        }
        if (parts.isEmpty()) return List.of();
        float[] qv = ai.embed(List.of(query)).get(0);
        R<SearchResults> resp = client().search(SearchParam.newBuilder()
                .withCollectionName(COLLECTION)
                .withPartitionNames(parts)
                .withMetricType(MetricType.IP)
                .withOutFields(List.of(F_ID, F_DOC, F_KB, F_CONTENT))
                .withTopK(topK)
                .withVectors(List.of(toList(qv)))
                .withVectorFieldName(F_VECTOR)
                .withParams("{\"nprobe\":16}")
                .build());
        if (resp.getException() != null) throw new IllegalStateException("milvus search: " + resp.getException().getMessage());
        List<Hit> out = new ArrayList<>();
        SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
        var ids = wrapper.getIDScore(0);
        var contents = wrapper.getFieldData(F_CONTENT, 0);
        var docs = wrapper.getFieldData(F_DOC, 0);
        var kbs = wrapper.getFieldData(F_KB, 0);
        for (int i = 0; i < ids.size(); i++) {
            double score = ids.get(i).getScore();
            if (score < minScore) continue;
            out.add(new Hit(ids.get(i).getLongID(), num(docs.get(i)), num(kbs.get(i)),
                    String.valueOf(contents.get(i)), score));
        }
        return out;
    }

    /** 分区内精确计数（SDK query 分页；REST 时代被拒的 count，SDK 用 iterator 语义正确解）。 */
    @Override
    public long countByKb(Long kbId) {
        ensureCollection();
        String part = partitionName(kbId);
        R<io.milvus.grpc.QueryResults> resp = client().query(QueryParam.newBuilder()
                .withCollectionName(COLLECTION).withPartitionNames(List.of(part))
                .withOutFields(List.of(F_ID))
                .withExpr(F_KB + " == " + kbId)
                .withLimit(16384L)
                .build());
        if (resp.getException() != null) return 0;   // 分区不存在=0 条
        return new QueryResultsWrapper(resp.getData()).getRowCount();
    }

    // ---------- internal ----------

    private void deleteWhere(String coll, String part, String filter) {
        var b = DeleteParam.newBuilder().withCollectionName(coll).withExpr(filter);
        if (part != null) b.withPartitionName(part);
        R<MutationResult> r = client().delete(b.build());
        if (r.getException() != null)
            throw new IllegalStateException("milvus delete: " + r.getException().getMessage());
    }

    public static String partitionName(Long kbId) { return "kb_" + kbId; }

    private void ensureCollection() {
        if (collectionReady) return;
        synchronized (this) {
            if (collectionReady) return;
            R<Boolean> has = client().hasCollection(HasCollectionParam.newBuilder().withCollectionName(COLLECTION).build());
            if (Boolean.TRUE.equals(has.getData())) { collectionReady = true; return; }
            R<?> created = client().createCollection(CreateCollectionParam.newBuilder()
                    .withCollectionName(COLLECTION)
                    .withShardsNum(1)
                    .addFieldType(FieldType.newBuilder().withName(F_ID).withDataType(DataType.Int64).withPrimaryKey(true).withAutoID(false).build())
                    .addFieldType(FieldType.newBuilder().withName(F_KB).withDataType(DataType.Int64).build())
                    .addFieldType(FieldType.newBuilder().withName(F_DOC).withDataType(DataType.Int64).build())
                    .addFieldType(FieldType.newBuilder().withName(F_CONTENT).withDataType(DataType.VarChar).withMaxLength(8192).build())
                    .addFieldType(FieldType.newBuilder().withName(F_VECTOR).withDataType(DataType.FloatVector).withDimension(dim).build())
                    .build());
            if (created.getException() != null) throw new IllegalStateException("milvus createCollection: " + created.getException().getMessage());
            // SDK 显式 HNSW/IP（REST 时代只能 AUTOINDEX）——向量已归一化，IP=余弦，与 pgvector 对齐
            R<?> idx = client().createIndex(CreateIndexParam.newBuilder()
                    .withCollectionName(COLLECTION).withFieldName(F_VECTOR)
                    .withIndexType(IndexType.HNSW).withMetricType(MetricType.IP)
                    .withExtraParam("{\"M\":16,\"efConstruction\":200}")
                    .withSyncMode(Boolean.TRUE).build());
            if (idx.getException() != null) throw new IllegalStateException("milvus createIndex: " + idx.getException().getMessage());
            R<?> load = client().loadCollection(LoadCollectionParam.newBuilder().withCollectionName(COLLECTION).build());
            if (load.getException() != null) throw new IllegalStateException("milvus load: " + load.getException().getMessage());
            log.info("milvus(SDK) collection {} created (HNSW/IP dim={})", COLLECTION, dim);
            collectionReady = true;
        }
    }

    /** 新库首写前建分区（hasPartition 幂等）。 */
    private void ensurePartition(Long kbId) {
        String part = partitionName(kbId);
        R<Boolean> has = client().hasPartition(io.milvus.param.partition.HasPartitionParam.newBuilder()
                .withCollectionName(COLLECTION).withPartitionName(part).build());
        if (Boolean.TRUE.equals(has.getData())) return;
        R<?> r = client().createPartition(CreatePartitionParam.newBuilder()
                .withCollectionName(COLLECTION).withPartitionName(part).build());
        if (r.getException() != null) throw new IllegalStateException("milvus createPartition: " + r.getException().getMessage());
    }

    private static List<Float> toList(float[] a) {
        List<Float> l = new ArrayList<>(a.length);
        for (float f : a) l.add(f);
        return l;
    }
    private static long num(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
    private static String cut(String s) { return s.length() > 8000 ? s.substring(0, 8000) : s; }

    @PreDestroy
    public void close() {
        MilvusServiceClient c = client;
        if (c != null) { try { c.close(); } catch (Exception ignore) {} }
    }
}
