package com.kb.document.web;

import com.kb.common.api.BizCode;
import com.kb.common.api.BizException;
import com.kb.common.api.Result;
import com.kb.domain.chat.ConversationService;
import com.kb.domain.entity.ApiKey;
import com.kb.domain.entity.User;
import com.kb.domain.kb.ApiKeyService;
import com.kb.document.rag.RagService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 开放 API（M10.6）：外部程序用 API Key 直接问指定知识库，返回结构化 JSON（非 SSE）。
 * 鉴权走 Authorization: Bearer <kb_明文>，与站内 Sa-Token 会话体系完全隔离
 * （白名单放行 /api/open/**，由本控制器自己按 key 认证——见 SaTokenConfig）。
 *
 * <h3>会话隔离策略</h3>
 * 每次请求默认无状态（新建临时会话，问完即留档但对外不可见）；传 conversationId
 * 则复用——但必须属于该 key 的主人，防跨账号串会话。多轮上下文由 RagService 内部按会话历史拼装。
 */
@RestController
@RequestMapping("/api/open")
public class OpenApiController {

    private final ApiKeyService keyService;
    private final RagService rag;
    private final ConversationService convs;

    public OpenApiController(ApiKeyService keyService, RagService rag, ConversationService convs) {
        this.keyService = keyService;
        this.rag = rag;
        this.convs = convs;
    }

    public record AskReq(List<Long> kbIds, String question, Long conversationId) {}

    @PostMapping("/ask")
    public Result<Map<String, Object>> ask(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                           @RequestBody AskReq req) {
        String bearer = authHeader != null && authHeader.startsWith("Bearer ") ? authHeader.substring(7).trim() : null;
        if (req.question() == null || req.question().isBlank())
            throw BizException.of(BizCode.PARAM_INVALID, "question 必填");
        ApiKey k = keyService.authenticate(bearer, req.kbIds());
        User owner = keyService.ownerOf(k);

        Long convId = req.conversationId();
        if (convId == null) {
            convId = convs.create(owner, req.kbIds(), "API").getId();
        }
        RagService.AskResult res = rag.ask(owner, convId, req.question(), null);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("answer", res.answer());
        out.put("citations", res.citations().stream().map(c -> Map.of(
                "no", c.no(), "chunkId", c.chunkId(), "docId", c.docId(),
                "snippet", c.content(), "score", Math.round(c.score() * 1000.0) / 1000.0)).toList());
        out.put("conversationId", convId);
        out.put("elapsedMs", res.elapsedMs());
        return Result.ok(out);
    }
}
