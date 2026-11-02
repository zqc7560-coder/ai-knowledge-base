package com.kb.rag.vector;

import java.util.Collection;
import java.util.List;

/**
 * 向量存储抽象（M6 兑现 docs/01 的"预留可切换"承诺）。
 *
 * <h3>为什么接口在这一层</h3>
 * 语义单元 = "切片入库 / 按库检索 / 按文档删 / 计数"，都是业务动词；
 * embedding 计算不进接口——它属于 AI 层（AiClientFactory），存储实现只接收现成向量？
 * 不：接口方法收【文本】，实现内部调 embed 再写库。这样做 pgvector 与 Milvus 两个实现
 * 对上层完全同构（调用方 RagService/DocumentService 零分支），是当初承诺
 * "切换只改配置不改代码"的落点。代价：每个实现都注入 AiClientFactory，可接受。
 *
 * <h3>实现选择</h3>
 * kb.vector.store=pgvector（默认，生产够用）| milvus（大语料/复杂标量过滤场景）。
 */
public interface VectorStore {

    /** 一条待入库切片（chunkId 全局唯一=幂等键）。 */
    record ChunkRow(Long chunkId, Long kbId, Long docId, String content) {}

    /** 一条命中结果；score 为相似度（越大越相关，内积语义，-1..1 附近）。 */
    record Hit(Long chunkId, Long docId, Long kbId, String content, double score) {}

    /** 向量化并写入（upsert 幂等）。返回写入行数。 */
    int upsert(List<ChunkRow> rows);

    /** 按文档批量物理删除（文档删除/重向量化时联动）。 */
    void deleteByDocIds(Collection<Long> docIds);

    /** M9.4：单切片下线（人工禁用）。实现按 chunk_id 索引删。 */
    void deleteByChunkIds(Collection<Long> chunkIds);

    /** 按知识库批量物理删除（重建索引/跨存储迁移演练用，M6）。 */
    void deleteByKbIds(Collection<Long> kbIds);

    /** 向量 topK 检索，限定 kbIds 范围，按 minScore 过滤。 */
    List<Hit> search(String query, Collection<Long> kbIds, int topK, double minScore);

    long countByKb(Long kbId);

    /** 实现名（诊断端点/日志用）。 */
    String name();
}
