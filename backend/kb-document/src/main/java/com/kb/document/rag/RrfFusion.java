package com.kb.document.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）多通道结果融合。
 *
 * <h3>为什么用 RRF 而不是加权分数求和</h3>
 * 向量相似度（0~1 内积）与全文 BM25 分数（无界）量纲完全不同，加权求和需要对每个
 * 数据分布校准 scale，脆弱且调参成本高。RRF 只用<b>名次</b>不用分数：
 * score(d) = Σ 1/(k + rank_i(d))，k=60 是信息检索领域经验常数（TREC 实验值），
 * 含义是"名次差 1 的重要性随排名深度递减"。任何通道都天然适配、无需归一化。
 *
 * <h3>为什么还要 rerank</h3>
 * RRF 融合后仍用 cross-encoder（gte-rerank-v2）做最终精排——RRF 解决"两个召回通道
 * 公平入选"，rerank 解决"真正相关的排前面"，二者互补不互斥。
 */
public final class RrfFusion {

    private static final double K = 60.0;

    private RrfFusion() {}

    public record Fused(Long chunkId, double score) {}

    /**
     * @param channels 每个通道返回的 chunkId 有序列表（各自按相关性降序）。
     *                 同一 chunkId 出现在多个通道会叠加分数（这正是混合检索的意义：
     *                 向量+全文同时命中的块，几乎必然真的相关）。
     * @return 按 RRF 分数降序的融合列表，截断到 limit。
     */
    public static List<Fused> fuse(List<List<Long>> channels, int limit) {
        Map<Long, Double> acc = new LinkedHashMap<>();
        for (List<Long> ch : channels) {
            for (int rank = 0; rank < ch.size(); rank++) {
                acc.merge(ch.get(rank), 1.0 / (K + rank + 1), Double::sum);
            }
        }
        List<Fused> out = new ArrayList<>();
        acc.forEach((id, s) -> out.add(new Fused(id, s)));
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }
}
