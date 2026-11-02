package com.kb.rag.vector;

import com.kb.rag.ai.AiClientFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * pgvector 向量存储适配层。
 *
 * <h3>为什么这样设计</h3>
 * <ul>
 *   <li><b>为什么选 pgvector 而不是 Milvus（MVP 期）</b>：docs/01 决策——单 Postgres 容器
 *       即可满足运维成本最低的诉求，10 万 chunk 内 HNSW 延迟足够；若规模超预期，
 *       只需替换本类实现（方法签名即接口），调用方（RagService/DocumentService）零改动。</li>
 *   <li><b>为什么用内积 `&lt;#&gt;` 而不是余弦 `&lt;=&gt;`</b>：DashScope text-embedding-v3
 *       返回的向量已经 L2 归一化（模长=1），此时内积与余弦相似度数学等价，
 *       而 pgvector 的内积操作符配合 `vector_ip_ops` 索引类型支持 HNSW 增量构建，
 *       性能优于余弦操作符（pgvector 0.8 时期实测惯例）。
 *       注意：pgvector 的 `&lt;#&gt;` 返回的是<b>负内积</b>作为"距离"（距离越小越相似），
 *       所以读回时要取负号还原成相似度分数。</li>
 *   <li><b>为什么 embedding 与原文双写</b>：向量表冗余存一份 content，检索返回时
 *       无需再回 MySQL 联表取原文（一次网络往返）；同时 MySQL 的 document_chunks
 *       仍是事实源，删文档时两边都要清（见 DocumentService.delete）。</li>
 *   <li><b>为什么 upsert 用 ON CONFLICT DO UPDATE</b>：文档重试（FAILED→重新 EMBEDDING）
 *       可能对同一 chunk_id 重复入库；幂等 upsert 保证重试不产生重复向量。</li>
 * </ul>
 *
 * <h3>与密钥安全的关系</h3>
 * 本类不感知 API 密钥——所有向量化调用都经 {@link AiClientFactory}（全工程唯一持有
 * key 的类）发起，符合 docs/03 §L4 调用面收敛要求。
 */
@Component
public class PgVectorStore {

    /** pg 专用连接池（独立于业务 MySQL；8 连接 = 检索读多写少场景的够用值）。 */
    private final JdbcTemplate pg;
    private final DataSource ds;
    private final AiClientFactory ai;

    /**
     * pg 数据源故意【不】注册为 Spring Bean：Bean 化的第二数据源会被
     * MyBatis/DataSourceAutoConfiguration 误选为主库（实测踩过，登录查询打到 PG）。
     * 因此这里自建 HikariDataSource（非托管，随 JVM 退出回收）。
     */
    public PgVectorStore(AiClientFactory ai,
                         @Value("${kb.vector.url}") String url,
                         @Value("${kb.vector.user}") String user,
                         @Value("${kb.vector.password}") String password) {
        com.zaxxer.hikari.HikariDataSource hik = new com.zaxxer.hikari.HikariDataSource();
        hik.setJdbcUrl(url);
        hik.setUsername(user);
        hik.setPassword(password);
        hik.setMaximumPoolSize(8);
        hik.setPoolName("kb-pg");
        this.ds = hik;
        this.pg = new JdbcTemplate(hik);
        this.ai = ai;
    }

    public static class Scored {
        private final Long chunkId;
        private final Long docId;
        private final Long kbId;
        private final String content;
        private final double score;
        public Scored(Long chunkId, Long docId, Long kbId, String content, double score) {
            this.chunkId = chunkId; this.docId = docId; this.kbId = kbId;
            this.content = content; this.score = score;
        }
        public Long chunkId() { return chunkId; }
        public Long docId() { return docId; }
        public Long kbId() { return kbId; }
        public String content() { return content; }
        public double score() { return score; }
    }

    /** 批量入库：chunks(id,text) -> embedding -> upsert。调用方控制批次大小（≤10 走单请求）。 */
    public int upsert(List<ChunkRow> rows) {
        if (rows.isEmpty()) return 0;
        List<String> texts = rows.stream().map(ChunkRow::content).toList();
        List<float[]> vecs = ai.embed(texts);
        if (vecs.size() != rows.size())
            throw new IllegalStateException("embedding 数量与切片不一致: " + vecs.size() + " vs " + rows.size());
        String sql = "INSERT INTO kb_vectors (chunk_id, kb_id, doc_id, content, embedding) "
                + "VALUES (?,?,?,?,?::vector) ON CONFLICT (chunk_id) DO UPDATE SET embedding = EXCLUDED.embedding";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < rows.size(); i++) {
                ChunkRow r = rows.get(i);
                ps.setLong(1, r.chunkId());
                ps.setLong(2, r.kbId());
                ps.setLong(3, r.docId());
                ps.setString(4, r.content());
                ps.setString(5, toPgVector(vecs.get(i)));
                ps.addBatch();
            }
            int[] done = ps.executeBatch();
            int n = 0; for (int x : done) n += Math.max(x, 0);
            return n;
        } catch (SQLException e) {
            throw new RuntimeException("pgvector upsert failed: " + e.getClass().getSimpleName(), e);
        }
    }

    public record ChunkRow(Long chunkId, Long kbId, Long docId, String content) {}

    public void deleteByDocIds(Collection<Long> docIds) {
        if (docIds.isEmpty()) return;
        String in = String.join(",", docIds.stream().map(String::valueOf).toList());
        pg.update("DELETE FROM kb_vectors WHERE doc_id IN (" + in + ")");
    }

    /** 向量 topK（按库过滤）。score = 内积（-1..1）。 */
    public List<Scored> search(String query, Collection<Long> kbIds, int topK, double minScore) {
        if (kbIds.isEmpty()) return List.of();
        float[] qv = ai.embed(List.of(query)).get(0);
        String in = String.join(",", kbIds.stream().map(String::valueOf).toList());
        String sql = "SELECT chunk_id, doc_id, kb_id, content, embedding <#> ?::vector AS dist "
                + "FROM kb_vectors WHERE kb_id IN (" + in + ") ORDER BY embedding <#> ?::vector LIMIT ?";
        List<Scored> out = new ArrayList<>();
        pg.query(sql, rs -> {
            double score = -rs.getDouble("dist"); // <#> 是负内积距离
            if (score >= minScore) {
                out.add(new Scored(rs.getLong("chunk_id"), rs.getLong("doc_id"),
                        rs.getLong("kb_id"), rs.getString("content"), score));
            }
        }, toPgVector(qv), toPgVector(qv), topK);
        return out;
    }

    public long countByKb(Long kbId) {
        Long n = pg.queryForObject("SELECT count(*) FROM kb_vectors WHERE kb_id = ?", Long.class, kbId);
        return n == null ? 0 : n;
    }

    private static String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
