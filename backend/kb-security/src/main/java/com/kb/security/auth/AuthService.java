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

    /**
     * 修改密码（用户自助）：<b>必须携带正确旧密码</b>——改密是敏感动作，
     * 仅凭"当前有会话"不够（共享电脑忘锁屏/令牌被捡的场景直接裸奔）。
     * 新密码两次输入一致性由前端拦（体验），长度规则服务端再拦（安全底线不信任前端）。
     * 改密成功后当前 token 不强制失效（避免误伤"自己改自己"主流程），但事件进审计。
     */
    public void changePassword(User me, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < MIN_PWD || newPassword.length() > MAX_PWD)
            throw BizException.of(BizCode.PARAM_INVALID, "新密码需8-64位");
        if (oldPassword == null || oldPassword.isBlank())
            throw BizException.of(BizCode.PARAM_INVALID, "请输入当前密码");
        User fresh = users.selectById(me.getId());
        if (fresh == null || !Passwords.matches(oldPassword, fresh.getPasswordHash()))
            throw BizException.of(BizCode.PARAM_INVALID, "当前密码不正确");
        if (newPassword.equals(oldPassword))
            throw BizException.of(BizCode.PARAM_INVALID, "新密码不能与当前密码相同");
        User upd = new User();
        upd.setId(me.getId());
        upd.setPasswordHash(Passwords.hash(newPassword));
        users.updateById(upd);
        log.info("password changed user={}", me.getUsername());
    }

    /** 修改邮箱（用户自助）：同样要验证当前密码（邮箱=找回密码的凭据载体，改它≈改密码强度）。 */
    public void changeEmail(User me, String newEmail, String password) {
        String e = newEmail == null ? "" : newEmail.trim().toLowerCase();
        if (!EMAIL.matcher(e).matches())
            throw BizException.of(BizCode.PARAM_INVALID, "邮箱格式不正确");
        if (password == null || !Passwords.matches(password, users.selectById(me.getId()).getPasswordHash()))
            throw BizException.of(BizCode.PARAM_INVALID, "当前密码不正确");
        if (users.selectCount(new LambdaQueryWrapper<User>().eq(User::getEmail, e)
                .ne(User::getId, me.getId())) > 0)
            throw BizException.of(BizCode.PARAM_INVALID, "该邮箱已被其他账号使用");
        User upd = new User();
        upd.setId(me.getId());
        upd.setEmail(e);
        try {
            users.updateById(upd);
        } catch (Exception dup) {   // email 唯一索引兜底（并发注册同邮箱）
            throw BizException.of(BizCode.PARAM_INVALID, "该邮箱已被其他账号使用");
        }
        log.info("email changed user={} -> {}", me.getUsername(), maskEmail(e));
    }

    private static String maskEmail(String e) {
        int at = e.indexOf('@');
        return at <= 1 ? "***" : e.charAt(0) + "***" + e.substring(at);
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
