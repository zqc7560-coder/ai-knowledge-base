package com.kb.security.web;

import com.kb.common.api.Result;
import com.kb.domain.entity.KbInvite;
import com.kb.domain.entity.KbRequest;
import com.kb.domain.kb.InviteService;
import com.kb.security.auth.AuthService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 邀请链接 + 入伙申请（M10.5）。
 * 管理端点走登录态；预览端点走 /api/public/ 白名单（SaTokenConfig 已放行）——
 * 未登录者拿到链接先看到"库名+档位"再决定注册/登录，公开信息仅止于此，不含库内内容。
 */
@RestController
@RequestMapping("/api")
public class InviteController {

    private final InviteService invite;
    private final AuthService auth;

    public InviteController(InviteService invite, AuthService auth) {
        this.invite = invite;
        this.auth = auth;
    }

    // ---------- EDIT 者管理 ----------

    @PostMapping("/kb/{kbId}/invites")
    public Result<KbInvite> create(@PathVariable Long kbId, @RequestBody(required = false) Map<String, Object> body) {
        body = body == null ? Map.of() : body;
        String perm = (String) body.getOrDefault("permission", null);
        Integer maxUses = body.get("maxUses") == null ? null : ((Number) body.get("maxUses")).intValue();
        Integer days = body.get("days") == null ? null : ((Number) body.get("days")).intValue();
        return Result.ok(invite.createInvite(auth.currentUser(), kbId, perm, maxUses, days));
    }

    @GetMapping("/kb/{kbId}/invites")
    public Result<List<KbInvite>> list(@PathVariable Long kbId) {
        return Result.ok(invite.listInvites(auth.currentUser(), kbId));
    }

    @DeleteMapping("/invites/{id}")
    public Result<Void> revoke(@PathVariable Long id) {
        invite.revokeInvite(auth.currentUser(), id);
        return Result.ok(null);
    }

    /** 已登录者直接接受邀请。 */
    @PostMapping("/invites/accept")
    public Result<Void> accept(@RequestBody Map<String, String> body) {
        invite.acceptInvite(auth.currentUser(), body.get("token"));
        return Result.ok(null);
    }

    // ---------- 公开预览（白名单） ----------

    @GetMapping("/public/invite/{token}")
    public Result<Map<String, Object>> preview(@PathVariable String token) {
        return Result.ok(invite.previewInvite(token));
    }

    // ---------- 申请-审批 ----------

    @PostMapping("/kb/{kbId}/requests")
    public Result<KbRequest> apply(@PathVariable Long kbId) {
        return Result.ok(invite.apply(auth.currentUser(), kbId));
    }

    @GetMapping("/kb/{kbId}/requests")
    public Result<List<Map<String, Object>>> pending(@PathVariable Long kbId) {
        return Result.ok(invite.pendingRequests(auth.currentUser(), kbId));
    }

    @PostMapping("/requests/{id}/decide")
    public Result<Void> decide(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        boolean approve = Boolean.TRUE.equals(body.get("approve"));
        String perm = (String) body.getOrDefault("permission", null);
        invite.decide(auth.currentUser(), id, approve, perm);
        return Result.ok(null);
    }
}
