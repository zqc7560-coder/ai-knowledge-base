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

    public AuthController(AuthService auth) { this.auth = auth; }

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
