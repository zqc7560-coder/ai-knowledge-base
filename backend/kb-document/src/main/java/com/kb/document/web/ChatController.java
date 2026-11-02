package com.kb.document.web;

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
                          com.kb.domain.kb.KbAccessService access) {
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
            try {
                RagService.AskResult res = rag.ask(me, req.conversationId(), req.question(),
                        d -> sendQuiet(emitter, "delta", d));
                emitter.send(SseEmitter.event().name("citations")
                        .data(rag.citationsDetail(me, res.citations()), MediaType.APPLICATION_JSON));
                emitter.send(SseEmitter.event().name("done")
                        .data(Map.of("elapsedMs", res.elapsedMs(), "chars", res.answer().length())));
                emitter.complete();
            } catch (BizException | AiUpstreamException e) {
                sendQuiet(emitter, "error", Map.of("code", e instanceof BizException b ? b.getCode() : 4001,
                        "message", e.getMessage() == null ? "AI 服务异常" : e.getMessage()));
                emitter.complete();
            } catch (Exception e) {
                log.warn("ask failed reqId={}", com.kb.common.api.RequestIds.current(), e);
                sendQuiet(emitter, "error", Map.of("code", 1003, "message", "服务内部错误"));
                emitter.complete();
            }
        });
        emitter.onCompletion(() -> {});
        emitter.onTimeout(emitter::complete);
        return emitter;
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

    private static void sendQuiet(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data,
                    data instanceof String ? MediaType.TEXT_PLAIN : MediaType.APPLICATION_JSON));
        } catch (Exception clientGone) {
            // 客户端断开：静默；上游流在下一个 send 失败或 done 后由池线程收尾
        }
    }
}
