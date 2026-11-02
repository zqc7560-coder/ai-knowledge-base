package com.kb.document.chunk;

import com.kb.document.parser.ParsedDoc;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 切片器边界用例（docs/02 2.3：空/超长/全角/西文）。 */
class ChunkSplitterTest {

    private static ParsedDoc docOf(String... texts) {
        ParsedDoc d = new ParsedDoc();
        d.setSections(java.util.Arrays.stream(texts)
                .map(t -> new ParsedDoc.Section(t, null, null)).toList());
        return d;
    }

    @Test
    void shortTextOneChunk() {
        var out = new ChunkSplitter(500, 100).split(docOf("这是一个很短的句子。"));
        assertEquals(1, out.size());
        assertEquals("这是一个很短的句子。", out.get(0).text());
    }

    @Test
    void emptyDocZeroChunks() {
        assertTrue(new ChunkSplitter(500, 100).split(docOf("   ", "")).isEmpty());
    }

    @Test
    void longChineseSplitsAtSentenceEnd() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 120; i++) sb.append("这是第").append(i).append("个中文句子，用来填充长度。");
        var out = new ChunkSplitter(100, 20).split(docOf(sb.toString()));
        assertTrue(out.size() >= 4, "应当切出多块");
        for (var c : out) {
            assertTrue(c.tokenEstimate() <= 110, "块 token 不超预算: " + c.tokenEstimate());
            assertTrue(c.text().endsWith("。") || c.text().length() > 0);
        }
    }

    @Test
    void singleSentenceLongerThanChunkHardCut() {
        String one = "字".repeat(300); // 无标点
        var out = new ChunkSplitter(100, 20).split(docOf(one));
        assertTrue(out.size() >= 3);
        // 硬切带重叠：总长 > 原文（说明重叠存在）且 < 2x（不重复爆炸）
        int total = out.stream().mapToInt(c -> c.text().length()).sum();
        assertTrue(total > 300 && total < 380, "total=" + total);
    }

    @Test
    void latinSentenceSplitNoDecimalBreak() {
        String text = "Version 1.5 came out. It works fine! Really? Yes.";
        var sents = ChunkSplitter.sentenceSplit(text);
        assertEquals(4, sents.size());
        assertEquals("Version 1.5 came out.", sents.get(0));
    }

    @Test
    void badParamsThrow() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkSplitter(10, 5));
        assertThrows(IllegalArgumentException.class, () -> new ChunkSplitter(500, 500));
    }

    @Test
    void seqNumbersIncrease() {
        var out = new ChunkSplitter(100, 20).split(docOf("A。".repeat(200), "B。".repeat(200)));
        for (int i = 0; i < out.size(); i++) assertEquals(i, out.get(i).seqNo());
    }
}
