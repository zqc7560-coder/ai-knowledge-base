package com.kb.document.web;

import com.kb.common.api.Result;
import com.kb.domain.entity.DocPermission;
import com.kb.domain.kb.KbAccessService;
import com.kb.document.service.DocPermissionService;
import com.kb.security.auth.AuthService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 文档级权限管理端点（M10.6）——全部要求库 EDIT 权限（服务层统一裁决）。 */
@RestController
@RequestMapping("/api/documents/{docId}")
public class DocPermController {

    private final DocPermissionService svc;
    private final AuthService auth;

    public DocPermController(DocPermissionService svc, AuthService auth) {
        this.svc = svc;
        this.auth = auth;
    }

    @PutMapping("/restricted")
    public Result<Void> setRestricted(@PathVariable Long docId, @RequestBody Map<String, Boolean> body) {
        svc.setRestricted(auth.currentUser(), docId, Boolean.TRUE.equals(body.get("restricted")));
        return Result.ok(null);
    }

    @GetMapping("/perms")
    public Result<List<DocPermission>> list(@PathVariable Long docId) {
        return Result.ok(svc.listPerms(auth.currentUser(), docId));
    }

    @PostMapping("/perms")
    public Result<Void> grant(@PathVariable Long docId, @RequestBody Map<String, Object> body) {
        svc.grant(auth.currentUser(), docId, ((Number) body.get("userId")).longValue(),
                (String) body.getOrDefault("perm", "READ"));
        return Result.ok(null);
    }

    @DeleteMapping("/perms/{userId}")
    public Result<Void> revoke(@PathVariable Long docId, @PathVariable Long userId) {
        svc.revoke(auth.currentUser(), docId, userId);
        return Result.ok(null);
    }
}
