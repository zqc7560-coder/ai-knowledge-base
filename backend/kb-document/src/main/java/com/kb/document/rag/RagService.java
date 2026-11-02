package com.kb.document.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kb.common.api.BizCode;
import com.kb.common.api.BizException;
import com.kb.domain.chat.ConversationService;
import com.kb.domain.entity.Conversation;
import com.kb.domain.entity.Document;
import com.kb.domain.entity.DocumentChunk;
import com.kb.domain.entity.KnowledgeBase;
import com.kb.domain.entity.Message;
import com.kb.domain.entity.User;
import com.kb.domain.kb.KbAccessService;
import com.kb.domain.mapper.DocumentChunkMapper;
import com.kb.domain.mapper.DocumentMapper;
import com.kb.domain.mapper.KnowledgeBaseMapper;
import com.kb.rag.ai.AiClientFactory;
import com.kb.rag.ai.AiUpstreamException;
import com.kb.rag.prompt.RagPrompts;
import com.kb.rag.vector.PgVectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * RAG 引擎 v1（docs/02 M3）：查询改写 -> 向量检索 -> rerank -> 上下文组装 -> SSE 流式生成。
 * 混合检索（全文通道 + RRF）在 M4 接入，本类结构已按可插拔召回设计。
 */
@Slf4j
@Service
public class RagService {

    public record StreamHandler(Consumer<String> onDelta, Consumer<Object> onCitations, Consumer<String> onError) {}

    public record AskResult(String answer, List<RagPrompts.Cited> citations, long elapsedMs) {}

    private final AiClientFactory ai;
    private final PgVectorStore vectors;
    private final ConversationService convs;
    private final KbAccessService access;
    private final KnowledgeBaseMapper kbMapper;
    private final DocumentMapper docMapper;
    private final DocumentChunkMapper chunkMapper;
    private final com.kb.domain.mapper.MessageMapper msgMapper;
    private final JdbcTemplate mysql;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;

    public RagService(AiClientFactory ai, PgVectorStore vectors, ConversationService convs,
                      KbAccessService access, KnowledgeBaseMapper kbMapper, DocumentMapper docMapper,
                      DocumentChunkMapper chunkMapper, com.kb.domain.mapper.MessageMapper msgMapper,
                      DataSource dataSource,
                      org.springframework.data.redis.core.StringRedisTemplate redis) {
        this.ai = ai;
        this.vectors = vectors;
        this.convs = convs;
        this.access = access;
        this.kbMapper = kbMapper;
        this.docMapper = docMapper;
        this.chunkMapper = chunkMapper;
        this.msgMapper = msgMapper;
        this.mysql = new JdbcTemplate(dataSource);
        this.redis = redis;
    }

    /** 问答主入口（同步返回全量 + citations；SSE 侧调用 askStream）。 */
    public AskResult ask(User me, Long conversationId, String question, Consumer<String> deltaSink) {
        long t0 = System.currentTimeMillis();
        if (question == null || question.isBlank() || question.length() > 2000)
            throw BizException.of(BizCode.PARAM_INVALID, "问题需 1-2000 字符");
        Conversation conv = convs.loadOwned(me, conversationId);
        quotaCheck(me);

        List<Long> kbIds = ConversationService.split(conv.getKbIds());
        KnowledgeBase firstKb = access.loadExisting(kbIds.get(0));
        int topK = kbIds.size() == 1 ? firstKb.getTopK() : 5;
        double threshold = kbIds.size() == 1 ? firstKb.getScoreThreshold().doubleValue() : 0.25;

        // 1. 多轮指代改写（最近 5 条用户消息；当前问题不含代词时可跳过以省调用）
        String searchQuery = rewriteIfNeeded(conv, question);

        // 2. 向量召回（放大 4 倍给 rerank 留空间）
        List<PgVectorStore.Scored> recall = vectors.search(searchQuery, kbIds, Math.min(topK * 4, 20), 0.0);
        if (recall.isEmpty()) {
            String refuse = RagPrompts.refuse();
            if (deltaSink != null) deltaSink.accept(refuse);
            persist(me, conv, question, refuse, List.of(), System.currentTimeMillis() - t0);
            return new AskResult(refuse, List.of(), System.currentTimeMillis() - t0);
        }

        // 3. rerank（失败降级：直接用向量分数前 topK）
        List<PgVectorStore.Scored> ranked;
        try {
            List<String> docs = recall.stream().map(PgVectorStore.Scored::content).toList();
            List<AiClientFactory.RerankItem> rr = ai.rerank(searchQuery, docs, topK);
            ranked = new ArrayList<>();
            for (var item : rr) if (item.score() >= threshold) ranked.add(recall.get(item.index()));
            if (ranked.isEmpty()) ranked = recall.subList(0, Math.min(topK, recall.size()));
        } catch (Exception e) {
            log.warn("rerank degraded: {}", e.getClass().getSimpleName());
            ranked = recall.stream().sorted(Comparator.comparingDouble((PgVectorStore.Scored s) -> s.score()).reversed())
                    .limit(topK).toList();
        }

        // 4. 上下文组装
        RagPrompts.Assembled ctx = RagPrompts.assemble(ranked);

        // 5. 生成（流式或同步）
        List<Map<String, String>> msgs = new ArrayList<>();
        msgs.add(Map.of("role", "system", "content", RagPrompts.system(ctx.context())));
        // 最近 4 轮历史（user/assistant 交替）
        for (Message h : convs.history(me, conv.getId(), 8)) {
            if ("assistant".equals(h.getRole()) && h.getContent().startsWith(RagPrompts.refuse().substring(0, 12))) continue;
            msgs.add(Map.of("role", h.getRole(), "content", truncate(h.getContent(), 1200)));
        }
        msgs.add(Map.of("role", "user", "content", question));

        String answer;
        if (deltaSink != null) {
            StringBuilder sb = new StringBuilder();
            answer = ai.chatStream(msgs, d -> { sb.append(d); deltaSink.accept(d); });
            if (sb.length() == 0) answer = "(模型未返回内容，请重试)";
        } else {
            answer = ai.chat(msgs, 0.2, 1500);
        }

        persist(me, conv, question, answer, ctx.citations(), System.currentTimeMillis() - t0);
        return new AskResult(answer, ctx.citations(), System.currentTimeMillis() - t0);
    }

    private String rewriteIfNeeded(Conversation conv, String question) {
        boolean hasPronoun = question.matches(".*(它|他|她|这|那|其|以上|上述|它们).*");
        if (!hasPronoun) return question;
        // 归属者视角取历史（loadOwned 已在 ask() 校验过当前用户权限，历史仅本人可见）
        List<String> recent = historyForOwner(conv).stream()
                .filter(m -> "user".equals(m.getRole())).map(Message::getContent).toList();
        if (recent.isEmpty()) return question;
        try {
            String rewritten = ai.chat(List.of(Map.of("role", "user",
                    "content", RagPrompts.rewritePrompt(recent.subList(Math.max(0, recent.size() - 5), recent.size()), question))), 0.0, 100).trim();
            return rewritten.isBlank() || rewritten.length() > 300 ? question : rewritten;
        } catch (Exception e) {
            return question; // 改写失败用原问题
        }
    }

    private List<Message> historyForOwner(Conversation conv) {
        return msgMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conv.getId()).orderByDesc(Message::getId).last("limit 10"))
                .reversed();
    }

    private void persist(User me, Conversation conv, String question, String answer,
                         List<RagPrompts.Cited> cites, long elapsed) {
        convs.append(conv.getId(), "user", question, null, null, null, null, null);
        String citeIds = cites.isEmpty() ? null :
                cites.stream().map(c -> String.valueOf(c.chunkId())).reduce("", (a, b) -> a.isEmpty() ? b : a + "," + b);
        convs.append(conv.getId(), "assistant", answer, citeIds, null, estTokens(answer), (int) elapsed, ai.chatModel());
        usageBump(me);
    }

    /** Redis 配额：每人每日 300 问。 */
    private void quotaCheck(User me) {
        String key = "quota:ask:" + me.getId() + ":" + java.time.LocalDate.now();
        Long n = redis.opsForValue().increment(key);
        if (n != null && n == 1) redis.expire(key, java.time.Duration.ofDays(2));
        if (n != null && n > 300) throw BizException.of(BizCode.AI_QUOTA_EXCEEDED, "今日问答额度已用完（300/天）");
    }

    private void usageBump(User me) {
        redis.opsForHash().increment("usage:" + java.time.LocalDate.now(), "u" + me.getId() + ":ask", 1);
    }

    /** M4 关键词通道用（本阶段仅向量，留方法占位并真实查全文）。 */
    List<DocumentChunk> keywordRecall(String query, List<Long> kbIds, int limit) {
        String in = String.join(",", kbIds.stream().map(String::valueOf).toList());
        // ngram 全文（MySQL 8）——RRF 融合 M4 接
        return chunkMapper.selectList(new LambdaQueryWrapper<DocumentChunk>()
                .in(DocumentChunk::getKbId, kbIds).last("limit " + limit));
    }

    public Map<String, Object> citationsDetail(User me, List<RagPrompts.Cited> cites) {
        Set<Long> docIds = new LinkedHashSet<>(cites.stream().map(RagPrompts.Cited::docId).toList());
        Map<Long, Document> docMap = new LinkedHashMap<>();
        if (!docIds.isEmpty()) {
            for (Document d : docMapper.selectBatchIds(docIds)) docMap.put(d.getId(), d);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (var c : cites) {
            Document d = docMap.get(c.docId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("no", c.no());
            m.put("chunkId", c.chunkId());
            m.put("docId", c.docId());
            m.put("docName", d == null ? "已删除文档" : d.getFilename());
            m.put("snippet", c.content());
            m.put("score", Math.round(c.score() * 1000.0) / 1000.0);
            out.add(m);
        }
        return Map.of("citations", out);
    }

    private static String truncate(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }

    private static Integer estTokens(String s) {
        return s == null ? 0 : (int) Math.ceil(s.length() / 1.6);
    }
}
