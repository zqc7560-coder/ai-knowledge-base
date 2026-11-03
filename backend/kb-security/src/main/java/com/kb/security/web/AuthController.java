package com.kb.security.web;

import com.kb.common.api.Result;
import com.kb.domain.entity.User;
import com.kb.security.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final com.kb.domain.audit.AuditService audit;

    public AuthController(AuthService auth, com.kb.domain.audit.AuditService audit) {
        this.auth = auth;
        this.audit = audit;
    }

    public record RegisterReq(@NotBlank String username, @NotBlank String email, @NotBlank String password) {}
    public record LoginReq(@NotBlank String username, @NotBlank String password) {}

    @PostMapping("/register")
    public Result<Map<String, String>> register(@RequestBody @Validated RegisterReq req, HttpServletRequest raw) {
        String token = auth.register(new AuthService.RegisterCmd(req.username(), req.email(), req.password()), raw);
        return Result.ok(Map.of("token", token));
    }

    @PostMapping("/login")
    public Result<Map<String, String>> login(@RequestBody @Validated LoginReq req, HttpServletRequest raw) {
        String token = auth.login(req.username(), req.password(), raw);
        return Result.ok(Map.of("token", token));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        auth.logout();
        return Result.ok(null);
    }

    public record ChangePwdReq(@NotBlank String oldPassword, @NotBlank String newPassword,
                               @NotBlank String confirmPassword) {}
    public record ChangeEmailReq(@NotBlank String email, @NotBlank String password) {}

    /** 修改密码（自助）：旧密码校验在服务端；confirm 二次输入一致服务端也核（不信任前端）。 */
    @PostMapping("/password/change")
    public Result<Void> changePassword(@RequestBody @Validated ChangePwdReq req) {
        var me = auth.currentUser();
        if (!req.newPassword().equals(req.confirmPassword()))
            return Result.fail(1001, "两次输入的新密码不一致");
        auth.changePassword(me, req.oldPassword(), req.newPassword());
        audit.record(me, "user.pwdchange", "user#" + me.getId(), "self-service");
        return Result.ok(null);
    }

    /** 修改邮箱（自助）：需当前密码——邮箱是找回凭据，改它等同类校验强度。 */
    @PostMapping("/email/change")
    public Result<Void> changeEmail(@RequestBody @Validated ChangeEmailReq req) {
        var me = auth.currentUser();
        auth.changeEmail(me, req.email(), req.password());
        audit.record(me, "user.emailchange", "user#" + me.getId(), "self-service");
        return Result.ok(null);
    }

    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        User u = auth.currentUser();
        return Result.ok(Map.of(
                "id", u.getId(),
                "username", u.getUsername(),
                "email", u.getEmail(),
                "role", u.getRole()));
    }
}
