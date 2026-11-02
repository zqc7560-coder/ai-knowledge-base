package com.kb.security.auth;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kb.common.api.BizCode;
import com.kb.common.api.BizException;
import com.kb.domain.entity.User;
import com.kb.domain.mapper.UserMapper;
import com.kb.security.login.LoginRateLimiter;
import com.kb.security.password.Passwords;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AuthService {
    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9_-]{3,20}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final int MIN_PWD = 8, MAX_PWD = 64;

    private final UserMapper users;
    private final LoginRateLimiter limiter;

    public AuthService(UserMapper users, LoginRateLimiter limiter) {
        this.users = users;
        this.limiter = limiter;
    }

    public record RegisterCmd(String username, String email, String password) {}

    /** @return token */
    public String register(RegisterCmd cmd, HttpServletRequest req) {
        if (!USERNAME.matcher(cmd.username()).matches())
            throw BizException.of(BizCode.PARAM_INVALID, "用户名需为3-20位字母/数字/下划线/中划线");
        if (!EMAIL.matcher(cmd.email()).matches())
            throw BizException.of(BizCode.PARAM_INVALID, "邮箱格式不正确");
        if (cmd.password() == null || cmd.password().length() < MIN_PWD || cmd.password().length() > MAX_PWD)
            throw BizException.of(BizCode.PARAM_INVALID, "密码需8-64位");
        boolean exists = users.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, cmd.username())) > 0
            || users.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, cmd.email())) > 0;
        if (exists) throw BizException.of(BizCode.PARAM_INVALID, "用户名或邮箱已被占用");
        // 唯一键兜底：并发下 selectCount+insert 有竞态，DB 唯一索引是最终防线
        User u = new User();
        u.setUsername(cmd.username());
        u.setEmail(cmd.email());
        u.setPasswordHash(Passwords.hash(cmd.password()));
        u.setRole("USER");
        u.setStatus(1);
        try {
            users.insert(u);
        } catch (Exception dup) {
            log.info("register conflict user={} (unique index)", cmd.username());
            throw BizException.of(BizCode.PARAM_INVALID, "用户名或邮箱已被占用");
        }
        return doLogin(u, req);
    }

    public String login(String username, String password, HttpServletRequest req) {
        String ip = clientIp(req);
        if (!limiter.tryAcquireAttempt(ip))
            throw BizException.of(BizCode.RATE_LIMITED, "尝试过于频繁，请稍后再试");
        if (limiter.locked(username))
            throw BizException.of(BizCode.RATE_LIMITED, "账号已临时锁定，请30分钟后再试");
        User u = users.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        // 防账号枚举：不存在与错密码同文案同耗时路径
        if (u == null || !Passwords.matches(password, u.getPasswordHash())) {
            limiter.recordFailure(username);
            throw BizException.of(BizCode.LOGIN_FAILED, "用户名或密码错误");
        }
        if (u.getStatus() != 1) throw BizException.of(BizCode.ACCOUNT_DISABLED, "账号已被禁用");
        limiter.clearFailures(username);
        return doLogin(u, req);
    }

    public void logout() { StpUtil.logout(); }

    public User currentUser() {
        long id = StpUtil.getLoginIdAsLong();
        User u = users.selectById(id);
        if (u == null) throw BizException.of(BizCode.UNAUTHORIZED, "登录状态已失效");
        return u;
    }

    private String doLogin(User u, HttpServletRequest req) {
        StpUtil.login(u.getId());
        // Sa-Token 上下文里带 IP，供审计（不落 token 本身）
        log.info("login ok user={} ip={}", u.getUsername(), clientIp(req));
        return StpUtil.getTokenValue();
    }

    public static String clientIp(HttpServletRequest req) {
        String x = req.getHeader("X-Forwarded-For");
        if (x != null && !x.isBlank()) return x.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
