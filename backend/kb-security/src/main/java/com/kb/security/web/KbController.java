package com.kb.security.web;

import com.kb.common.api.Result;
import com.kb.domain.entity.KbMember;
import com.kb.domain.entity.KnowledgeBase;
import com.kb.domain.kb.KbService;
import com.kb.security.auth.AuthService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/kb")
public class KbController {

    private final KbService kb;
    private final AuthService auth;
    private final com.kb.domain.audit.AuditService audit;

    public KbController(KbService kb, AuthService auth,
                           com.kb.domain.audit.AuditService audit) {
        this.kb = kb;
        this.auth = auth;
        this.audit = audit;
    }

    public record CreateReq(@NotBlank String name, String description, String visibility,
                            Integer chunkSize, Integer chunkOverlap, Integer topK,
                            java.math.BigDecimal scoreThreshold,
                            Boolean parentChild, Integer pcSpan,
                            Boolean hyde, Boolean multiQuery, String grp, String tags) {}

    @GetMapping
    public Result<List<KnowledgeBase>> list() {
        return Result.ok(kb.myVisible(auth.currentUser()));
    }

    @PostMapping
    public Result<KnowledgeBase> create(@RequestBody @Validated CreateReq req) {
        return Result.ok(kb.create(auth.currentUser(), toCmd(req)));
    }

    @PutMapping("/{id}")
    public Result<KnowledgeBase> update(@PathVariable Long id, @RequestBody CreateReq req) {
        var me = auth.currentUser();
        var old = kb.loadForUser(me, id);
        var out = kb.update(me, id, toCmd(req));
        if (req.visibility() != null && !req.visibility().equals(old.getVisibility()))
            audit.record(me, "kb.visibility", "kb#" + id, old.getVisibility() + "→" + req.visibility());
        return Result.ok(out);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        var me = auth.currentUser();
        kb.delete(me, id);
        audit.record(me, "kb.delete", "kb#" + id, null);
        return Result.ok(null);
    }

    @PostMapping("/{id}/members")
    public Result<Void> grant(@PathVariable Long id, @RequestBody Map<String, String> body) {
        var me = auth.currentUser();
        kb.grantMember(me, id, body.get("username"), body.get("permission"));
        audit.record(me, "member.grant", "kb#" + id, body.get("username") + "→" + body.get("permission"));
        return Result.ok(null);
    }

    @DeleteMapping("/{id}/members/{userId}")
    public Result<Void> revoke(@PathVariable Long id, @PathVariable Long userId) {
        var me = auth.currentUser();
        kb.revokeMember(me, id, userId);
        audit.record(me, "member.revoke", "kb#" + id, "user#" + userId);
        return Result.ok(null);
    }

    @GetMapping("/{id}/members")
    public Result<List<KbMember>> members(@PathVariable Long id) {
        return Result.ok(kb.listMembers(auth.currentUser(), id));
    }

    private static KbService.CreateCmd toCmd(CreateReq r) {
        return new KbService.CreateCmd(r.name(), r.description(), r.visibility(),
                r.chunkSize(), r.chunkOverlap(), r.topK(), r.scoreThreshold(),
                r.parentChild(), r.pcSpan(), r.hyde(), r.multiQuery(), r.grp(), r.tags());
    }
}
