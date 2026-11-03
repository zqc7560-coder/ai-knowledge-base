package com.kb.security.web;

import com.kb.common.api.Result;
import com.kb.security.auth.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 找回密码（M11.3）公开端点：/api/auth/password/reset-request + reset-confirm。
 * 防枚举：两接口返回形状恒定，文案不区分"邮箱是否注册"。
 * 限流/锁定全在 PasswordResetService（60s 重发 + 日限 5 + 验证错 5 次作废）。
 */
@Validated
@RestController
@RequestMapping("/api/auth/password")
public class PasswordResetController {

    public record ResetReq(@NotBlank @Pattern(regexp = "^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$", message = "邮箱格式不正确") String email) {}
    public record ConfirmReq(@NotBlank String email,
                             @NotBlank @Pattern(regexp = "^\\d{6}$", message = "验证码为6位数字") String code,
                             @NotBlank @Size(min = 8, max = 64, message = "密码需8-64位") String password) {}

    private final PasswordResetService svc;

    public PasswordResetController(PasswordResetService svc) {
        this.svc = svc;
    }

    @PostMapping("/reset-request")
    public Result<Void> request(@RequestBody ResetReq req, HttpServletRequest raw) {
        svc.requestReset(req.email(), com.kb.security.auth.AuthService.clientIp(raw));
        return Result.ok(null);
    }

    @PostMapping("/reset-confirm")
    public Result<Void> confirm(@RequestBody ConfirmReq req) {
        svc.confirmReset(req.email(), req.code(), req.password());
        return Result.ok(null);
    }

    /** 前端据此决定是否显示"忘记密码"入口（未配置 SMTP 时隐藏，避免点了报错）。 */
    @GetMapping("/reset-available")
    public Result<Boolean> available() {
        return Result.ok(svc.smtpConfigured());
    }
}
