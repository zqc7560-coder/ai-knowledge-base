package com.kb.document.rag;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.kb.domain.entity.DocumentChunk;
import com.kb.domain.mapper.DocumentChunkMapper;
import com.kb.rag.vector.VectorStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RagService 检索组装核心单测（M12.4 收官项）。
 *
 * <h3>测什么</h3>
 * parent-child 窗口扩展（small-to-big 的去重正确性——窗口交叠若处理错误，
 * 同一段原文会喂两遍，LLM 引用计数与预算全乱）与 RRF 融合排序（混合检索的合流数学）。
 * 这两块是 RAG 质量的核心算法，也是 docs/04 评估里出过问题的区域。
 *
 * <h3>怎么绕过 12 参构造器</h3>
 * mock(RagService, CALLS_REAL_METHODS) 创建不调构造器的实例，反射注入唯一用到的
 * chunkMapper 字段；私有方法反射调用。取舍：反射脆弱于改名——但这些方法签名稳定且
 * 测试断言的是行为（窗口合并/去重/排序），值得。
 */
class RagServiceUnitTest {

    static {
        var ba = new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(ba, DocumentChunk.class);
    }

    private RagService svcWith(DocumentChunkMapper chunkMapper) throws Exception {
        RagService svc = mock(RagService.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        Field f = RagService.class.getDeclaredField("chunkMapper");
        f.setAccessible(true);
        f.set(svc, chunkMapper);
        return svc;
    }

    @SuppressWarnings("unchecked")
    private List<VectorStore.Hit> expand(RagService svc, List<VectorStore.Hit> ranked, int span) throws Exception {
        Method m = RagService.class.getDeclaredMethod("expandWithParents", List.class, int.class);
        m.setAccessible(true);
        return (List<VectorStore.Hit>) m.invoke(svc, ranked, span);
    }

    @SuppressWarnings("unchecked")
    private List<VectorStore.Hit> rrf(RagService svc, List<VectorStore.Hit> a, List<VectorStore.Hit> b, int k) throws Exception {
        Method m = RagService.class.getDeclaredMethod("unionRrf", List.class, List.class, int.class);
        m.setAccessible(true);
        return (List<VectorStore.Hit>) m.invoke(svc, a, b, k);
    }

    private static VectorStore.Hit hit(long chunkId, long docId, String content, double score) {
        return new VectorStore.Hit(chunkId, docId, 1L, content, score);
    }

    private static DocumentChunk ch(long id, long docId, int seq, String content) {
        DocumentChunk c = new DocumentChunk();
        c.setId(id); c.setDocId(docId); c.setSeqNo(seq); c.setContent(content); c.setDisabled(0);
        return c;
    }

    @Test
    @DisplayName("父子窗口：单命中扩展为 seq±span 合并段")
    void singleHitExpandsWindow() throws Exception {
        DocumentChunkMapper cm = mock(DocumentChunkMapper.class);
        when(cm.selectById(20L)).thenReturn(ch(20L, 7L, 2, "中心段"));
        when(cm.selectList(any(Wrapper.class))).thenReturn(List.of(
                ch(18L, 7L, 0, "段0"), ch(19L, 7L, 1, "段1"), ch(20L, 7L, 2, "段2"), ch(21L, 7L, 3, "段3")));
        RagService svc = svcWith(cm);
        var out = expand(svc, List.of(hit(20L, 7L, "中心段", 0.9)), 2);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).content()).isEqualTo("段0\n段1\n段2\n段3");   // 按 seq 升序拼接
    }

    @Test
    @DisplayName("窗口交叠去重：低名次命中落入已覆盖窗口→退化原文，不重复喂")
    void overlappingHitsDedup() throws Exception {
        DocumentChunkMapper cm = mock(DocumentChunkMapper.class);
        when(cm.selectById(20L)).thenReturn(ch(20L, 7L, 2, "A"));
        when(cm.selectById(22L)).thenReturn(ch(22L, 7L, 3, "B"));        // 与 A 的窗口 [0..4] 相交
        when(cm.selectById(99L)).thenReturn(ch(99L, 8L, 9, "C"));        // 另一文档，独立窗口
        when(cm.selectList(any(Wrapper.class))).thenReturn(List.of(
                ch(20L, 7L, 2, "A"), ch(21L, 7L, 3, "A2"), ch(22L, 7L, 3, "B"), ch(23L, 7L, 4, "A3")));
        RagService svc = svcWith(cm);
        var out = expand(svc, List.of(hit(20L, 7L, "A", 0.9), hit(22L, 7L, "B", 0.8), hit(99L, 8L, "C", 0.7)), 2);
        assertThat(out).hasSize(3);
        assertThat(out.get(0).content()).contains("A2");                 // 第一条正常扩展
        assertThat(out.get(1).content()).isEqualTo("B");                 // 第二条交叠→退化 child 原文
        // 第三条不同文档：自己的窗口（selectList mock 返回同一批无所谓，断言它未被去重即可）
        assertThat(out.get(2).content()).isNotEqualTo("B");
    }

    @Test
    @DisplayName("无 seqNo 的旧行：原样返回不吞命中（兼容扩展前的数据）")
    void legacyRowWithoutSeqPassesThrough() throws Exception {
        DocumentChunkMapper cm = mock(DocumentChunkMapper.class);
        DocumentChunk noSeq = ch(30L, 7L, 0, "X"); noSeq.setSeqNo(null);
        when(cm.selectById(30L)).thenReturn(noSeq);
        RagService svc = svcWith(cm);
        var out = expand(svc, List.of(hit(30L, 7L, "X", 0.5)), 2);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).content()).isEqualTo("X");
    }

    @Test
    @DisplayName("RRF：双榜都进的 chunk 排名靠前于单榜头名")
    void rrfConsensusBeatsSingleTop() throws Exception {
        DocumentChunkMapper cm = mock(DocumentChunkMapper.class);
        RagService svc = svcWith(cm);
        // a 榜: A(第1) B(第2)；b 榜: B(第1) C(第2)。B 双榜=共识应第一
        var out = rrf(svc, List.of(hit(1, 1, "A", 1), hit(2, 1, "B", 1)),
                List.of(hit(2, 1, "B", 1), hit(3, 1, "C", 1)), 60);
        assertThat(out.get(0).chunkId()).isEqualTo(2L);
        assertThat(out.stream().map(VectorStore.Hit::chunkId).toList()).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    @DisplayName("RRF：同 id 在两榜的位置分相加，输出分数单调降序")
    void rrfSortedByScoreDesc() throws Exception {
        DocumentChunkMapper cm = mock(DocumentChunkMapper.class);
        RagService svc = svcWith(cm);
        var a = List.of(hit(1, 1, "A", 1), hit(2, 1, "B", 1), hit(3, 1, "C", 1));
        var b = List.of(hit(3, 1, "C", 1), hit(2, 1, "B", 1), hit(1, 1, "A", 1));
        var out = rrf(svc, a, b, 60);
        for (int i = 1; i < out.size(); i++)
            assertThat(out.get(i - 1).score()).isGreaterThanOrEqualTo(out.get(i).score());
    }
}
