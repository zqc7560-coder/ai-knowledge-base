package com.kb.document.web;

import com.kb.common.api.BizCode;
import com.kb.common.api.BizException;
import com.kb.common.api.Result;
import com.kb.domain.chat.ConversationService;
import com.kb.domain.entity.Conversation;
import com.kb.domain.entity.Message;
import com.kb.document.rag.RagService;
import com.kb.rag.ai.AiUpstreamException;
import com.kb.security.auth.AuthService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 问答与历史（docs/02 M3.3/3.5）：POST /api/chat/ask 走 SSE。 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final RagService rag;
    private final ConversationService convs;
    private final AuthService auth;
    private final ExecutorService ssePool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sse-ask"); t.setDaemon(true); return t;
    });

    public ChatController(RagService rag, ConversationService convs, AuthService auth,
                          com.kb.domain.mapper.DocumentChunkMapper chunkMapper,
                          com.kb.domain.mapper.DocumentMapper docMapper,
                          com.kb.domain.kb.KbAccessService access,
                          com.kb.domain.mapper.MessageMapper msgMapper,
                          com.kb.document.service.DocPermissionService docPermSvc) {
        this.msgMapper = msgMapper;
        this.docPermSvc = docPermSvc;
        this.rag = rag;
        this.convs = convs;
        this.auth = auth;
        this.chunkMapper = chunkMapper;
        this.docMapper = docMapper;
        this.access = access;
    }

    public record AskReq(@NotNull Long conversationId, @NotBlank String question) {}

    @PostMapping("/conversations")
    public Result<Conversation> create(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Long> kbIds = ((List<Number>) body.get("kbIds")).stream().map(Number::longValue).toList();
        String title = (String) body.get("title");
        return Result.ok(convs.create(auth.currentUser(), kbIds, title));
    }

    @GetMapping("/conversations")
    public Result<List<Conversation>> my() {
        return Result.ok(convs.my(auth.currentUser()));
    }

    @GetMapping("/conversations/{id}/messages")
    public Result<List<Message>> messages(@PathVariable Long id,
                                          @RequestParam(defaultValue = "50") int limit) {
        return Result.ok(convs.history(auth.currentUser(), id, limit));
    }

    @PutMapping("/conversations/{id}")
    public Result<Void> rename(@PathVariable Long id, @RequestBody Map<String, String> body) {
        convs.rename(auth.currentUser(), id, body.get("title"));
        return Result.ok(null);
    }

    @PatchMapping("/conversations/{id}/pin")
    public Result<Void> pin(@PathVariable Long id, @RequestParam boolean pinned) {
        convs.pin(auth.currentUser(), id, pinned);
        return Result.ok(null);
    }

    @DeleteMapping("/conversations/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        convs.delete(auth.currentUser(), id);
        return Result.ok(null);
    }

    /**
     * SSE 流式问答。事件协议（docs/01 §5）：
     *   citations（先行：引用列表 JSON）→ delta×N → done（统计）；异常 → error。
     * 客户端断开：emitter 回调中断上游流（chatStream 的 io 异常路径自然终止）。
     */
    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@RequestBody @Validated AskReq req) {
        SseEmitter emitter = new SseEmitter(180_000L);
        var me = auth.currentUser(); // 请求线程内取会话上下文
        ssePool.submit(() -> {
            // M7.2 归因：ssePool 线程无登录上下文，显式把 userId 绑进 UsageContext
            // （ask 全流程——改写 chat/生成 chatStream/rerank——都在此线程执行）
            com.kb.rag.usage.UsageContext.set(me.getId(), null);
            try {
                RagService.AskResult res = rag.ask(me, req.conversationId(), req.question(),
                        d -> sendQuiet(emitter, "delta", d));
                // M10 置信度：取本次引用最高 rerank/融合分；<0.30 提示前端"仅供参考"。
                // 口径写在 docs/07（阈值是经验值，rerank 开/关时分数分布不同，属展示层辅助非硬判据）。
                var det = rag.citationsDetail(me, res.citations());
                emitter.send(SseEmitter.event().name("citations").data(det, MediaType.APPLICATION_JSON));
                Object cs = det.get("citations");
                double maxScore = 0;
                if (cs instanceof java.util.List<?> l)
                    for (Object o : l)
                        if (o instanceof java.util.Map<?, ?> mm && mm.get("score") instanceof Number n)
                            maxScore = Math.max(maxScore, n.doubleValue());
                emitter.send(SseEmitter.event().name("done")
                        .data(Map.of("elapsedMs", res.elapsedMs(), "chars", res.answer().length(),
                                "maxScore", Math.round(maxScore * 1000.0) / 1000.0,
                                "lowConfidence", !res.citations().isEmpty() && maxScore < 0.30)));
                emitter.complete();
            } catch (BizException | AiUpstreamException e) {
                sendQuiet(emitter, "error", Map.of("code", e instanceof BizException b ? b.getCode() : 4001,
                        "message", e.getMessage() == null ? "AI 服务异常" : e.getMessage()));
                emitter.complete();
            } catch (Exception e) {
                log.warn("ask failed reqId={}", com.kb.common.api.RequestIds.current(), e);
                sendQuiet(emitter, "error", Map.of("code", 1003, "message", "服务内部错误"));
                emitter.complete();
            } finally {
                com.kb.rag.usage.UsageContext.clear();   // 池线程复用，必清（防串用户归因）
            }
        });
        emitter.onCompletion(() -> {});
        emitter.onTimeout(emitter::complete);
        return emitter;
    }

    /**
     * 重新生成前置（M10）：删掉会话最后一条 assistant 消息并返回上一条 user 问题原文，
     * 前端拿问题重新走 /ask。只删一条、必须紧邻尾部，防历史中段篡改（问答配对完整性）。
     */
    @PostMapping("/conversations/{convId}/regenerate")
    public Result<Map<String, Object>> regenerate(@PathVariable Long convId) {
        var me = auth.currentUser();
        convs.loadOwned(me, convId);
        var list = msgMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.kb.domain.entity.Message>()
                .eq(com.kb.domain.entity.Message::getConversationId, convId)
                .orderByDesc(com.kb.domain.entity.Message::getId).last("limit 2"));
        if (list.isEmpty() || !"assistant".equals(list.get(0).getRole()))
            throw BizException.of(BizCode.PARAM_INVALID, "尾部不是助手答案，无法重新生成");
        msgMapper.deleteById(list.get(0).getId());
        String lastUser = list.size() > 1 && "user".equals(list.get(1).getRole()) ? list.get(1).getContent() : null;
        if (lastUser == null) throw BizException.of(BizCode.PARAM_INVALID, "找不到原始问题");
        return Result.ok(Map.of("question", lastUser));
    }

    /** 编辑重问（M10.2b）：改历史提问并截断其后消息，前端拿返回的新问题文本重走 /ask。 */
    @PutMapping("/messages/{msgId}")
    public Result<Object> editQuestion(@PathVariable Long msgId, @RequestBody Map<String, String> body) {
        var m = convs.editQuestionAndTruncate(auth.currentUser(), msgId, body.get("content"));
        return Result.ok(Map.of("id", m.getId(), "content", m.getContent()));
    }

    /** 会话内消息搜索（M10.2a）：翻旧对话定位，不跨库。 */
    @GetMapping("/conversations/{convId}/search")
    public Result<Object> searchConv(@PathVariable Long convId, @RequestParam String q) {
        return Result.ok(convs.search(auth.currentUser(), convId, q));
    }

    /** 历史引用回放：按 chunkIds 取原文与文档名（权限=对 chunk 所属库至少 ASK）。 */
    @PostMapping("/citations")
    public Result<Object> citations(@RequestBody Map<String, List<Long>> body) {
        List<Long> ids = body.get("chunkIds");
        if (ids == null || ids.isEmpty()) return Result.ok(List.of());
        var me = auth.currentUser();
        var chunks = chunkMapper.selectBatchIds(ids);
        var docs = chunks.isEmpty() ? Map.<Long, com.kb.domain.entity.Document>of()
                : docMapper.selectBatchIds(chunks.stream().map(com.kb.domain.entity.DocumentChunk::getDocId)
                        .distinct().toList()).stream()
                .collect(java.util.stream.Collectors.toMap(com.kb.domain.entity.Document::getId, d -> d));
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (var c : chunks) {
            access.require(c.getKbId(), me, com.kb.domain.kb.KbAccessService.Perm.READ);
            var d = docs.get(c.getDocId());
            if (d != null && !docPermSvc.canSee(me, d)) continue;   // M10.6：受限文档引用不回放
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("chunkId", c.getId());
            m.put("docId", c.getDocId());
            m.put("docName", d == null ? "已删除文档" : d.getFilename());
            m.put("snippet", c.getContent().length() > 700 ? c.getContent().substring(0, 700) : c.getContent());
            m.put("pageNo", c.getPageNo());
            m.put("titlePath", c.getTitlePath());
            out.add(m);
        }
        return Result.ok(out);
    }

    private final com.kb.domain.mapper.DocumentChunkMapper chunkMapper;
    private final com.kb.domain.mapper.DocumentMapper docMapper;
    private final com.kb.domain.kb.KbAccessService access;
    private final com.kb.domain.mapper.MessageMapper msgMapper;
    private final com.kb.document.service.DocPermissionService docPermSvc;

    /**
     * 引用上下文窗口：给定 chunkId，返回同文档相邻 ±around 个切片（按序），
     * 供前端"原文预览"抽屉展示并高亮命中块。权限=对该 chunk 所属库 READ。
     */
    @PostMapping("/citations/window")
    public Result<Object> citationWindow(@RequestBody Map<String, Object> body) {
        Number cid = (Number) body.get("chunkId");
        int around = body.get("around") == null ? 2 : ((Number) body.get("around")).intValue();
        if (cid == null) return Result.ok(List.of());
        var me = auth.currentUser();
        var hit = chunkMapper.selectById(cid.longValue());
        if (hit == null) return Result.ok(List.of());
        access.require(hit.getKbId(), me, com.kb.domain.kb.KbAccessService.Perm.READ);
        var hitDoc = docMapper.selectById(hit.getDocId());
        if (hitDoc != null && !docPermSvc.canSee(me, hitDoc)) return Result.ok(List.of());  // M10.6
        int lo = Math.max(0, hit.getSeqNo() - around), hi = hit.getSeqNo() + around;
        var list = chunkMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<com.kb.domain.entity.DocumentChunk>()
                .eq(com.kb.domain.entity.DocumentChunk::getDocId, hit.getDocId())
                .between(com.kb.domain.entity.DocumentChunk::getSeqNo, lo, hi)
                .orderByAsc(com.kb.domain.entity.DocumentChunk::getSeqNo));
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (var c : list) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("seqNo", c.getSeqNo());
            m.put("content", c.getContent());
            m.put("pageNo", c.getPageNo());
            m.put("titlePath", c.getTitlePath());
            out.add(m);
        }
        return Result.ok(out);
    }

    private static void sendQuiet(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data,
                    data instanceof String ? MediaType.TEXT_PLAIN : MediaType.APPLICATION_JSON));
        } catch (Exception clientGone) {
            // 客户端断开：静默；上游流在下一个 send 失败或 done 后由池线程收尾
        }
    }
}
