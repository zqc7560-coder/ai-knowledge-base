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
 * pgvector 存储适配层（docs/01 决策：MVP=pgvector，预留抽象可切 Milvus）。
 * 文本经 AiClientFactory 向量化后写入 kb_vectors（HNSW 内积索引；上游向量已归一化，内积=余弦）。
 */
@Component
public class PgVectorStore {

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
