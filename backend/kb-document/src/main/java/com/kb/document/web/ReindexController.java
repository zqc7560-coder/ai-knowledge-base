package com.kb.document.web;

import com.kb.common.api.Result;
import com.kb.document.service.ReindexService;
import com.kb.domain.kb.KbAccessService;
import com.kb.security.auth.AuthService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 重建索引/向量库切换端点（M6，docs/02 2.5"一键重建索引"+M6"Milvus 切换演练"）。
 * 权限=库 EDIT（ReindexService 自身不查权限，控制器层统一裁决——与文档端点同构）。
 */
@RestController
@RequestMapping("/api/kb")
public class ReindexController {

    private final ReindexService reindex;
    private final KbAccessService access;
    private final AuthService auth;

    public ReindexController(ReindexService reindex, KbAccessService access, AuthService auth) {
        this.reindex = reindex;
        this.access = access;
        this.auth = auth;
    }

    @PostMapping("/{kbId}/reindex")
    public Result<Void> reindex(@PathVariable Long kbId) {
        access.require(kbId, auth.currentUser(), KbAccessService.Perm.EDIT);
        reindex.reindex(kbId);
        return Result.ok(null);
    }

    @PostMapping("/{kbId}/migrate")
    public Result<Void> migrate(@PathVariable Long kbId, @RequestParam String to) {
        access.require(kbId, auth.currentUser(), KbAccessService.Perm.EDIT);
        reindex.migrate(kbId, to);
        return Result.ok(null);
    }

    @GetMapping("/{kbId}/reindex-status")
    public Result<Map<String, Object>> status(@PathVariable Long kbId) {
        access.require(kbId, auth.currentUser(), KbAccessService.Perm.READ);
        return Result.ok(reindex.status(kbId));
    }
}
