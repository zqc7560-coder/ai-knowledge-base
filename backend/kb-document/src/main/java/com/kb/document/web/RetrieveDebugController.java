package com.kb.document.web;

import com.kb.common.api.BizCode;
import com.kb.common.api.BizException;
import com.kb.common.api.Result;
import com.kb.domain.chat.ConversationService;
import com.kb.domain.entity.KnowledgeBase;
import com.kb.domain.entity.User;
import com.kb.domain.kb.KbAccessService;
import com.kb.document.rag.RagService;
import com.kb.rag.ai.AiClientFactory;
import com.kb.rag.vector.VectorStore;
import com.kb.security.auth.AuthService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索调试面板后端（M8.3，Dify"检索测试"同款心智）。
 * 一次请求把管道各阶段中间态原样吐出：改写→向量路→全文路→RRF→rerank→最终喂料。
 * 只读、不改任何状态；权限=对目标库 ASK（能问就能看检索过程——不新增攻击面）。
 */
@RestController
@RequestMapping("/api/debug")
public class RetrieveDebugController {

    private final RagService rag;
    private final KbAccessService access;
    private final AuthService auth;

    public RetrieveDebugController(RagService rag, KbAccessService access, AuthService auth) {
        this.rag = rag;
        this.access = access;
        this.auth = auth;
    }

    public record Req(List<Long> kbIds, String question) {}

    @PostMapping("/retrieve")
    public Result<Map<String, Object>> retrieve(@RequestBody Req req) {
        if (req.kbIds() == null || req.kbIds().isEmpty() || req.question() == null || req.question().isBlank())
            throw BizException.of(BizCode.PARAM_INVALID, "kbIds 与 question 必填");
        User me = auth.currentUser();
        for (Long id : req.kbIds()) access.require(id, me, KbAccessService.Perm.ASK);
        return Result.ok(rag.debugRetrieve(me, req.kbIds(), req.question()));
    }
}
