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
 * RAG 引擎——"检索增强生成"的完整问答管道（docs/01 F6 的实现体）。
 *
 * <h3>一次提问经过的六个阶段（方法体从上到下即此顺序）</h3>
 * <ol>
 *   <li><b>配额闸门 quotaCheck</b>：Redis 每日计数（默认 300 问/人）。放在最前面是因为
 *       它不花 AI 额度就能拦掉滥用——省的是真金白银的上游调用。</li>
 *   <li><b>权限复核 loadOwned</b>：会话绑定的每个库都必须当前用户仍有 ASK 权限。
 *       历史会话存续期间库可能被删/撤权/转私有，每次提问都重新裁决，杜绝"老会话吃老权限"。</li>
 *   <li><b>查询改写 rewriteIfNeeded</b>：多轮对话里"那它呢？"这类指代句直接拿去向量检索
 *       必然跑偏，所以带代词时用 LLM 把追问改写成独立完整问题（见 RagPrompts.rewritePrompt）。
 *       改写失败静默回退原问题——检索宁可不完美也不能因增强功能报错。</li>
 *   <li><b>向量召回 recall</b>：向 pgvector 要 topK*4（最多 20）条候选。放大 4 倍是把
 *       "初筛宽、精筛严"的经典漏斗留给下一步 rerank——embedding 相似度是粗信号，
 *       真正该进 prompt 的少数几条要靠 cross-encoder 判。</li>
 *   <li><b>Rerank 重排</b>：gte-rerank-v2 对候选按"与问题的真实相关度"重新打分取 topK，
 *       并套用知识库阈值过滤假阳性。上游 rerank 偶尔超时/限流——catch 后降级用
 *       向量分数排序，<b>可用性优先于精度</b>。</li>
 *   <li><b>上下文组装 + 生成</b>：RagPrompts.assemble 给每条引用编号 [1][2]…，按 4000 字符
 *       预算裁剪（保头弃尾，因候选已按分数降序）；system prompt 强制"只用资料回答+标注编号+
 *       资料不足就拒答"，答案里用户就能核对来源。生成走 chatStream，token 级推给 SSE。</li>
 * </ol>
 *
 * <h3>为什么"无召回直接拒答，不发 LLM"</h3>
 * ① 省一次上游调用；② 空资料下 LLM 极易自由发挥产生幻觉，违背"基于知识库回答"的产品承诺。
 * 拒答文案同时指导用户下一步动作（检查上传状态/换关键词）。
 *
 * <h3>混合检索（M4）</h3>
 * 目前只有向量单通道；keywordRecall() 已就位（MySQL ngram 全文），M4 接 RRF 融合——
 * 解决专有名词、型号编号这类 embedding 不敏感的精确词漏召问题。
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

    /**
     * 检索模式开关（A/B 消融实验用，docs/02 4.2）：
     *  hybrid=false 退化为纯向量召回；rerank=false 跳过精排直接用融合名次。
     *  默认两者全开=生产配置。改配置重启即生效，不动代码。
     */
    @org.springframework.beans.factory.annotation.Value("${kb.rag.hybrid:true}")
    private boolean hybridEnabled;
    @org.springframework.beans.factory.annotation.Value("${kb.rag.rerank:true}")
    private boolean rerankEnabled;

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
        rateCheck(me);    // 分钟闸门：先于一切花钱动作
        quotaCheck(me);   // 日配额闸门

        List<Long> kbIds = ConversationService.split(conv.getKbIds());
        KnowledgeBase firstKb = access.loadExisting(kbIds.get(0));
        int topK = kbIds.size() == 1 ? firstKb.getTopK() : 5;
        double threshold = kbIds.size() == 1 ? firstKb.getScoreThreshold().doubleValue() : 0.25;

        // 1. 多轮指代改写（最近 5 条用户消息；当前问题不含代词时可跳过以省调用）
        String searchQuery = rewriteIfNeeded(conv, question);

        // 2. 混合召回（M4）：向量通道 + MySQL ngram 全文通道，RRF 名次融合。
        //    向量擅长语义近似但会漏"型号/专有名词"这类精确 token；全文正好互补。
        //    放大 4 倍（上限20）召回是给下一步 cross-encoder 精排留候选池——初筛宽、精筛严。
        int wideK = Math.min(topK * 4, 20);
        List<PgVectorStore.Scored> vecHits = vectors.search(searchQuery, kbIds, wideK, 0.0);
        List<PgVectorStore.Scored> recall;
        if (hybridEnabled) {
            List<DocumentChunk> kwHits = keywordRecall(searchQuery, kbIds, wideK);
            recall = fuseChannels(vecHits, kwHits, wideK);
        } else {
            recall = vecHits; // 消融：纯向量
        }
        if (recall.isEmpty()) {
            String refuse = RagPrompts.refuse();
            if (deltaSink != null) deltaSink.accept(refuse);
            persist(me, conv, question, refuse, List.of(), System.currentTimeMillis() - t0);
            return new AskResult(refuse, List.of(), System.currentTimeMillis() - t0);
        }

        // 3. rerank 精排（gte-rerank-v2 对候选重新打分取 topK）
        //    失败降级用召回分数排序——可用性优先于精度；
        //    消融开关 kb.rag.rerank=false 直接跳过精排，便于 A/B 对比精排贡献。
        List<PgVectorStore.Scored> ranked;
        if (!rerankEnabled) {
            ranked = new ArrayList<>(recall.subList(0, Math.min(topK, recall.size())));
        } else try {
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

    /**
     * 限流双闸门（docs/01 §7.7 防滥用省额度）：
     * ① 分钟级：登录用户 20 问/分钟（Redis INCR+TTL，key 按自然分钟滚动）。
     *    超了直接抛 1004，不转上游调用——拦截发生在花钱之前才有意义。
     * ② 日级：300 问/人/天（quotaCheck）。
     * 计数语义：先 +1 再比较，所以窗口首次请求 n=1；==1 时设过期，避免僵尸键。
     */
    private void rateCheck(User me) {
        String minute = String.valueOf(System.currentTimeMillis() / 60_000);
        String key = "rl:ask:" + me.getId() + ":" + minute;
        Long n = redis.opsForValue().increment(key);
        if (n != null && n == 1) redis.expire(key, java.time.Duration.ofMinutes(2));
        if (n != null && n > 20) throw BizException.of(BizCode.RATE_LIMITED, "提问过于频繁，请1分钟后再试（20次/分）");
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

    /**
     * 关键词召回通道：MySQL ngram 全文索引（document_chunks.ft_chunk，token=2）。
     * BM25 分数不返回（pgvector 那边有相似度即可排序），这里只要"命中名次"喂给 RRF。
     * query 侧做安全处理：截断长度 + 转义，防注入与超长查询拖垮 MATCH。
     */
    List<DocumentChunk> keywordRecall(String query, List<Long> kbIds, int limit) {
        if (kbIds.isEmpty()) return List.of();
        String q = query.length() > 64 ? query.substring(0, 64) : query;
        q = q.replace("'", "").replace("\"", "").replace("\\", "");
        String in = String.join(",", kbIds.stream().map(String::valueOf).toList());
        // MATCH>0 过滤未命中行；分数降序即名次（分数无界但不进 RRF 值，只要名次）。
        // document_chunks 无软删列：块与向量随文档删除物理清（DocumentService.delete），表里都是活数据。
        return mysql.query(
                "SELECT id, doc_id, kb_id, content FROM document_chunks " +
                "WHERE kb_id IN (" + in + ") " +
                "  AND MATCH(content) AGAINST (? IN NATURAL LANGUAGE MODE) > 0 " +
                "ORDER BY MATCH(content) AGAINST (? IN NATURAL LANGUAGE MODE) DESC LIMIT " + limit,
                (rs, i) -> {
                    DocumentChunk d = new DocumentChunk();
                    d.setId(rs.getLong("id"));
                    d.setDocId(rs.getLong("doc_id"));
                    d.setKbId(rs.getLong("kb_id"));
                    d.setContent(rs.getString("content"));
                    return d;
                }, q, q);
    }

    /**
     * 融合两通道结果：向量通道保 content/docId 映射，全文通道贡献名次。
     * RRF 分数作为合成 score 向下传给 rerank 层（rerank 失败降级时用它排序）。
     */
    List<PgVectorStore.Scored> fuseChannels(List<PgVectorStore.Scored> vecHits,
                                            List<DocumentChunk> kwHits, int limit) {
        Map<Long, PgVectorStore.Scored> byChunk = new LinkedHashMap<>();
        for (var v : vecHits) byChunk.put(v.chunkId(), v);
        // 全文通道里向量没召回的块，从 MySQL 取原文补进候选池（content 非空才能进 prompt）
        List<DocumentChunk> extras = kwHits.stream().filter(k -> !byChunk.containsKey(k.getId())).toList();
        for (var e : extras) byChunk.put(e.getId(),
                new PgVectorStore.Scored(e.getId(), e.getDocId(), e.getKbId(), e.getContent(), 0.0));
        if (extras.isEmpty() || kwHits.isEmpty()) return vecHits;

        List<Long> vecOrder = vecHits.stream().map(PgVectorStore.Scored::chunkId).toList();
        List<Long> kwOrder = kwHits.stream().map(DocumentChunk::getId).toList();
        List<RrfFusion.Fused> fused = RrfFusion.fuse(List.of(vecOrder, kwOrder), limit);
        List<PgVectorStore.Scored> out = new ArrayList<>();
        for (var f : fused) {
            var s = byChunk.get(f.chunkId());
            if (s != null) out.add(new PgVectorStore.Scored(s.chunkId(), s.docId(), s.kbId(), s.content(), f.score()));
        }
        return out;
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
