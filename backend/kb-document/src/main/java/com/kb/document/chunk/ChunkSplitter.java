package com.kb.document.chunk;

import com.kb.document.parser.ParsedDoc;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归分隔符切片：段落 -> 句子（。！？.!? 与换行）-> 字符硬切，目标 token 数 + 重叠。
 * 中文按 1 字符≈1 token 粗估（DashScope 中文实际约 0.6~1 token/字，估算保守不超预算即可）。
 * 每个 chunk 继承其来源段落的页码/标题路径（跨段合并时记第一段）。
 */
public class ChunkSplitter {

    public record Chunk(String text, Integer pageNo, String titlePath, int seqNo, int tokenEstimate) {}

    private final int targetTokens;
    private final int overlapTokens;

    public ChunkSplitter(int targetTokens, int overlapTokens) {
        if (targetTokens < 50) throw new IllegalArgumentException("chunk 目标至少 50");
        if (overlapTokens < 0 || overlapTokens >= targetTokens) throw new IllegalArgumentException("重叠需 0..<target");
        this.targetTokens = targetTokens;
        this.overlapTokens = overlapTokens;
    }

    public List<Chunk> split(ParsedDoc doc) {
        List<Chunk> out = new ArrayList<>();
        int seq = 0;
        for (ParsedDoc.Section s : doc.getSections()) {
            if (s.getText() == null || s.getText().isBlank()) continue;
            for (String piece : splitText(s.getText())) {
                if (piece.isBlank()) continue;
                out.add(new Chunk(piece, s.getPageNo(), s.getTitlePath(), seq++, est(piece)));
            }
        }
        return out;
    }

    /** 段落文本 -> 若干 <=target 的切片，带句尾重叠。 */
    List<String> splitText(String text) {
        List<String> sentences = sentenceSplit(text);
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curTok = 0;
        for (String sent : sentences) {
            int sentTok = est(sent);
            // 单句超预算：先冲掉当前缓冲，再对超长句硬切
            if (sentTok > targetTokens) {
                if (cur.length() > 0) { chunks.add(cur.toString()); cur.setLength(0); curTok = 0; }
                chunks.addAll(hardCut(sent));
                continue;
            }
            if (curTok + sentTok > targetTokens && cur.length() > 0) {
                chunks.add(cur.toString());
                // 重叠：回退 overlap 个 token 的句子
                String tail = overlapTail(chunks.get(chunks.size() - 1));
                cur.setLength(0); cur.append(tail); curTok = est(tail);
            }
            cur.append(sent);
            curTok += sentTok;
        }
        if (cur.length() > 0) chunks.add(cur.toString());
        return chunks;
    }

    /** 按中日英句末标点切句，保留标点；引号/括号内不切。 */
    static List<String> sentenceSplit(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n'
                    || ((c == '.' || c == '!' || c == '?') && isEndOfLatinSentence(text, i))) {
                out.add(text.substring(start, i + 1).trim());
                start = i + 1;
            }
        }
        if (start < text.length()) {
            String last = text.substring(start).trim();
            if (!last.isEmpty()) out.add(last);
        }
        out.removeIf(String::isEmpty);
        return out;
    }

    private static boolean isEndOfLatinSentence(String text, int i) {
        // 跳过 1.5 / e.g. 等小数与缩写：前一个是数字且后一个是字母，或后无空格
        if (i + 1 < text.length() && !Character.isWhitespace(text.charAt(i + 1))) return false;
        if (i > 0 && Character.isDigit(text.charAt(i - 1)) && i + 1 < text.length() && Character.isLetter(text.charAt(i + 1)))
            return false;
        return true;
    }

    List<String> hardCut(String text) {
        List<String> out = new ArrayList<>();
        int limit = targetTokens;
        int pos = 0;
        while (pos < text.length()) {
            int end = Math.min(text.length(), advanceByTokens(text, pos, limit));
            out.add(text.substring(pos, end).trim());
            if (end >= text.length()) break;
            int back = Math.max(0, retreatByTokens(text, end, overlapTokens));
            pos = back > pos ? back : end; // 保证推进，避免死循环
        }
        out.removeIf(String::isBlank);
        return out;
    }

    /** 从 idx 前进 n token 大约对应 n 个西文单词或 1.6n 个 CJK 字符。 */
    private int advanceByTokens(String text, int idx, int n) {
        double remaining = n;
        int i = idx;
        while (i < text.length() && remaining > 0) {
            char c = text.charAt(i);
            if (isCjk(c)) { remaining -= 1.0; i++; }
            else if (Character.isWhitespace(c)) { i++; }
            else {
                int j = i; while (j < text.length() && !isCjk(text.charAt(j)) && !Character.isWhitespace(text.charAt(j))) j++;
                remaining -= Math.max(0.25, (j - i) / 4.0);
                i = j;
            }
        }
        return i;
    }

    private int retreatByTokens(String text, int idx, int n) {
        double remaining = n;
        int i = idx;
        while (i > 0 && remaining > 0) {
            char c = text.charAt(i - 1);
            if (isCjk(c)) { remaining -= 1.0; i--; }
            else if (Character.isWhitespace(c)) { i--; }
            else { int j = i; while (j > 0 && !isCjk(text.charAt(j - 1)) && !Character.isWhitespace(text.charAt(j - 1))) j--; remaining -= Math.max(0.25, (i - j) / 4.0); i = j; }
        }
        return i;
    }

    static int est(String s) {
        if (s == null) return 0;
        int cjk = 0, other = 0;
        for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (isCjk(c)) cjk++; else if (!Character.isWhitespace(c)) other++; }
        return cjk + (int) Math.ceil(other / 4.0);
    }

    private String overlapTail(String chunk) {
        if (overlapTokens <= 0) return "";
        int from = retreatByTokens(chunk, chunk.length(), overlapTokens);
        return chunk.substring(from);
    }

    private static boolean isCjk(char c) {
        return c >= '\u4e00' && c <= '\u9fff' || c >= '\u3000' && c <= '\u303f' || c >= '\uff00' && c <= '\uffef';
    }
}
