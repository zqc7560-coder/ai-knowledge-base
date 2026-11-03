package com.kb.rag.vector;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 向量存储路由（M6 切换机制核心）。
 *
 * <h3>为什么用 Router 而不是 @ConditionalOnProperty 二选一</h3>
 * 条件装配（只装一个 Bean）能实现"改配置切库"，但那样"从 pgvector 迁移到 Milvus"
 * 无法在同一进程内同时拿到两个实现——迁移需要"旧库读+新库写"并存。
 * Router 让两个实现都常驻容器（各自惰性建集合/连库，未用的那个不碰网络），
 * 按 kb.vector.store 把调用转发给 active；migrate 则显式取另一个。
 *
 * <h3>@Primary 的意义</h3>
 * 上层 RagService/DocumentService 仍按接口 VectorStore 注入（零分支），Spring 注入到本 Router。
 */
@Primary
@Component
public class VectorStoreRouter implements VectorStore {

    private final Map<String, VectorStore> byName;
    private final VectorStore active;

    public VectorStoreRouter(PgVectorStore pg, MilvusSdkStore milvus,
                             @Value("${kb.vector.store:pgvector}") String store) {
        this.byName = Map.of("pgvector", pg, "milvus", milvus);
        this.active = byName.getOrDefault(store, pg);   // 非法配置回退 pgvector（不因拼写错误起不来）
    }

    public VectorStore active() { return active; }
    public VectorStore byName(String name) { return byName.get(name); }

    @Override public int upsert(List<ChunkRow> rows) { return active.upsert(rows); }
    @Override public void deleteByDocIds(Collection<Long> ids) { active.deleteByDocIds(ids); }
    @Override public void deleteByKbIds(Collection<Long> ids) { active.deleteByKbIds(ids); }
    @Override public void deleteByChunkIds(Collection<Long> ids) { active.deleteByChunkIds(ids); }
    @Override public List<Hit> search(String q, Collection<Long> ids, int topK, double min) { return active.search(q, ids, topK, min); }
    @Override public long countByKb(Long id) { return active.countByKb(id); }
    @Override public String name() { return active.name(); }
}
