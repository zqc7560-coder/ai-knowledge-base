package com.kb.rag.prompt;

import com.kb.rag.vector.VectorStore;

import java.util.List;

/**
 * RAG 上下文组装 + Prompt 模板（docs/01 F6）。
 * 引用编号 [1]..[n] 出现在答案里，前端映射到 citations 列表。
 */
public final class RagPrompts {
    public static final int CONTEXT_CHAR_BUDGET = 4000;

    private RagPrompts() {}

    public record Cited(int no, Long chunkId, Long docId, String content, double score) {}

    /** 按 rerank/融合分数降序裁剪到预算；返回带编号的引用与拼装文本块。 */
    public static Assembled assemble(List<VectorStore.Hit> hits) {
        StringBuilder sb = new StringBuilder();
        List<Cited> cites = new java.util.ArrayList<>();
        int used = 0;
        int no = 0;
        for (var h : hits) {
            String snippet = h.content().length() > 700 ? h.content().substring(0, 700) + "…" : h.content();
            int add = snippet.length() + 40;
            if (used + add > CONTEXT_CHAR_BUDGET && !cites.isEmpty()) break;
            no++;
            cites.add(new Cited(no, h.chunkId(), h.docId(), snippet, h.score()));
            sb.append('[').append(no).append("] ").append(snippet).append("\n\n");
            used += add;
        }
        return new Assembled(sb.toString(), cites);
    }

    public record Assembled(String context, List<Cited> citations) {}

    private static final String SYSTEM = """
        你是知识库问答助手。规则：
        1) 只依据下面提供的资料区块回答；资料中没有的内容不要编造。
        2) 引用资料时在相关句子末尾标注编号，如 [1] 或 [2][3]。
        3) 资料不足或无关时，明确回答"知识库中未找到相关内容"，可补充一句需要什么资料。
        4) 使用与用户提问相同的语言，回答简洁准确，可用 Markdown 列表。

        【资料区块】
        %s
        【资料区块结束】""";

    public static String system(String context) {
        return String.format(SYSTEM, context.isBlank() ? "（本知识库暂无相关资料）" : context);
    }

    /** 无召回时的拒答（不发 LLM，省额度）。 */
    public static String refuse() {
        return "知识库中未找到与该问题相关的内容。建议：① 确认已上传相关文档并处于 READY 状态；② 换个提问关键词；③ 若文档为新上传，稍等片刻再试。";
    }

    /** 查询改写 prompt：把带指代的追问改写为独立问题（用于检索）。 */
    public static String rewritePrompt(List<String> recentQuestions, String current) {
        StringBuilder h = new StringBuilder("以下是用户与助手的最近对话中的问题（旧→新）：\n");
        for (String q : recentQuestions) h.append("- ").append(q).append('\n');
        h.append("请把最新一条追问改写成**不需要上下文也能理解**的独立问题，只输出改写后的问题本身，不要解释。\n最新追问：").append(current);
        return h.toString();
    }
}
