package com.kb.document.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** RRF 融合算法单测（名次制、多通道叠加、limit）。 */
class RrfFusionTest {

    @Test
    void singleChannelKeepsOrder() {
        var out = RrfFusion.fuse(List.of(List.of(10L, 20L, 30L)), 10);
        assertEquals(List.of(10L, 20L, 30L), out.stream().map(RrfFusion.Fused::chunkId).toList());
    }

    @Test
    void consensusItemBeatsSingletons() {
        // 40 在两通道都排第 2，应胜过仅单通道第一的 10
        var vec = List.of(10L, 40L);
        var kw = List.of(40L, 99L);
        var out = RrfFusion.fuse(List.of(vec, kw), 10);
        assertEquals(40L, out.get(0).chunkId());
    }

    @Test
    void rrfFormulaExact() {
        var out = RrfFusion.fuse(List.of(List.of(1L), List.of(1L)), 5);
        double expected = 1 / 61.0 + 1 / 61.0; // k=60, rank=1
        assertEquals(expected, out.get(0).score(), 1e-9);
    }

    @Test
    void limitTruncates() {
        var out = RrfFusion.fuse(List.of(List.of(1L, 2L, 3L, 4L, 5L)), 3);
        assertEquals(3, out.size());
    }
}
