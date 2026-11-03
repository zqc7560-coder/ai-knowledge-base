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
 * 只读、不改任何状态。
 *
 * <h3>权限口径（2026-11-03 按用户要求收紧为 ADMIN 专属）</h3>
 * 原设计"ASK 即可用（能问就能看）"存在现实漏洞：PUBLIC 公开库让所有登录用户天然 ASK，
 * 等于普通用户都能开面板——还能借 myRank≥2 的下拉看到全库检索细节（片段内容/分数），
 * 公开库存有敏感试稿时这就是旁路读面。现改为：
 * ① 端点类级 @SaCheckRole("ADMIN")——普通用户直接 2002；
 * ② ADMIN 可调试任意库（含他人私有库：运维排障视角，跳过 ASK 校验但记录日志）；
 * ③ 前端入口同步只给管理员显示（安全在服务端，显隐只是体验）。
 */
@lombok.extern.slf4j.Slf4j
@cn.dev33.satoken.annotation.SaCheckRole("ADMIN")
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
        User me = auth.currentUser();   // 类级注解已保证 ADMIN 才能到这里
        for (Long id : req.kbIds()) access.loadExisting(id);   // 仅校验库存在；ADMIN 跨 owner 调试是运维能力
        log.info("debug retrieve by admin={} kbs={}", me.getUsername(), req.kbIds());
        return Result.ok(rag.debugRetrieve(me, req.kbIds(), req.question()));
    }
}
