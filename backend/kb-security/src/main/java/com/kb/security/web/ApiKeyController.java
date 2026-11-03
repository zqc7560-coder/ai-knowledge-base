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

    public ApiKeyController(ApiKeyService svc, AuthService auth) {
        this.svc = svc;
        this.auth = auth;
    }

    public record CreateReq(String name, List<Long> kbIds) {}

    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody CreateReq req) {
        return Result.ok(svc.create(auth.currentUser(), req.name(), req.kbIds()));
    }

    @GetMapping
    public Result<List<ApiKey>> list() {
        return Result.ok(svc.list(auth.currentUser()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> revoke(@PathVariable Long id) {
        svc.revoke(auth.currentUser(), id);
        return Result.ok(null);
    }
}
