package com.kb.security.web;

import com.kb.common.api.Result;
import com.kb.domain.entity.ApiKey;
import com.kb.domain.kb.ApiKeyService;
import com.kb.security.auth.AuthService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 开放 API 密钥自管理（M10.6）：登录用户建/列/撤自己的 key（明文仅创建响应一次）。 */
@RestController
@RequestMapping("/api/keys")
public class ApiKeyController {

    private final ApiKeyService svc;
    private final AuthService auth;
    private final com.kb.domain.audit.AuditService audit;

    public ApiKeyController(ApiKeyService svc, AuthService auth, com.kb.domain.audit.AuditService audit) {
        this.svc = svc;
        this.auth = auth;
        this.audit = audit;
    }

    public record CreateReq(String name, List<Long> kbIds) {}

    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody CreateReq req) {
        var me = auth.currentUser();
        var out = svc.create(me, req.name(), req.kbIds());
        audit.record(me, "key.create", "key#" + out.get("id"), req.name());
        return Result.ok(out);
    }

    @GetMapping
    public Result<List<ApiKey>> list() {
        return Result.ok(svc.list(auth.currentUser()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> revoke(@PathVariable Long id) {
        var me = auth.currentUser();
        svc.revoke(me, id);
        audit.record(me, "key.revoke", "key#" + id, null);
        return Result.ok(null);
    }
}
