package com.kb.document.chunk;

import com.kb.document.parser.ParsedDoc;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归分隔符文本切片器（RAG 质量的基石——切得好坏直接决定检索命中与答案质量）。
 *
 * <h3>切分策略（为什么这样切）</h3>
 * <ol>
 *   <li><b>先按句子边界聚合，凑满 targetTokens 成一块</b>：而不是无脑按固定字符数硬切。
 *       原因：语义检索以"句子/段落完整性"为最优，句子被拦腰切断会让 embedding 语义漂移，
 *       召回时相关度骤降。所以本类先 sentenceSplit 拆句，再把句子累加到接近预算才断开。</li>
 *   <li><b>递归降级</b>：正常路径按句切；若单个句子就超过整个预算（如 PDF 里的一整段表格
 *       或代码块无标点），才用 hardCut 按 token 硬切，避免产出超预算的畸形块。</li>
 *   <li><b>重叠 overlap</b>：相邻块保留尾部若干 token 作重叠，防止"关键句正好跨在切点两侧"
 *       导致两端都检索不到。经验值 target 的 10~20%（默认 500±100）。重叠会让块总量略增，
 *       是用一点存储/召回冗余换命中率。</li>
 * </ol>
 *
 * <h3>中文 token 估算（为什么不精确分词）</h3>
 * DashScope 无公开的精确 token 计数接口，这里用保守估算：1 个 CJK 字符 ≈ 1 token，
 * 4 个西文字符 ≈ 1 token（见 {@link #est}）。估算偏向"宁可多切几块也不超预算"，
 * 因为超预算的块塞进 prompt 会被上游截断，比多一块更糟。
 *
 * <h3>边界情况</h3>
 * 空文档返回空列表；纯标点/空白块被过滤；构造参数非法（target&lt;50 或 overlap≥target）直接抛异常，
 * 不允许运行期出现"重叠吃掉整个块"的死循环风险。
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
