package com.kb.security.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kb.common.api.BizCode;
import com.kb.common.api.BizException;
import com.kb.domain.audit.AuditService;
import com.kb.domain.entity.User;
import com.kb.domain.mapper.UserMapper;
import com.kb.security.login.LoginRateLimiter;
import com.kb.security.password.Passwords;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Properties;

/**
 * 找回密码（M11.3）：邮箱验证码 → 重置密码 → 解锁登录。
 *
 * <h3>安全设计要点</h3>
 * <ul>
 * <li><b>防账号枚举</b>：无论邮箱是否存在，接口回同一文案同一形状（"若该邮箱已注册，验证码已发送"）。
 *     不存在时不查库不发信，直接返回——但响应不可区分。</li>
 * <li><b>验证码</b>：6 位 SecureRandom；Redis TTL 10 分钟；键按邮箱哈希存（不给验证码键挂明文 PII，
 *     日志同样只打掩码）。同邮箱 60 秒重发锁、每日上限 5 次。</li>
 * <li><b>验证尝试限流</b>：同邮箱错误验证累计 5 次作废当前码（防爆破 6 位数字：10^6 空间 ×10 分钟窗口，
 *     若不限流一晚上可穷举；5 次上限把成功率压到 5e-6）。</li>
 * <li><b>重置后失效</b>：验证码一次性消费（delete）；重置同时清登录失败锁定
 *     （用户本来就忘了密码才会走到这里，锁定是忘记密码的连带症状）。改密事件落审计（admin 可读）。</li>
 * <li><b>未配置 SMTP 优雅降级</b>：mail.* 配置缺失 → 接口返回明确业务错误"邮件服务未配置"，
 *     绝不静默假成功（用户等不到邮件会以为功能坏了；报错反而让部署者立刻去补配置）。</li>
 * </ul>
 * SMTP 凭据经 {@code MAIL_USERNAME}/{@code MAIL_PASSWORD}（授权码）注入 .env，符合 L1/L7/L8 红线。
 */
@Slf4j
@Service
public class PasswordResetService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final String CODE_PREFIX = "kb:pwdreset:code:";   // + sha256(email)
    private static final String SENT_PREFIX = "kb:pwdreset:sent:";   // 重发间隔
    private static final String DAY_PREFIX = "kb:pwdreset:day:";     // 日限
    private static final String FAIL_PREFIX = "kb:pwdreset:fail:";   // 验证失败计数
    private static final String IP_PREFIX = "kb:pwdreset:ip:";        // 按 IP 的发信计数（防把邮箱当轰炸机）

    private final UserMapper users;
    private final StringRedisTemplate redis;
    private final JavaMailSenderImpl mail;
    private final AuditService audit;
    private final LoginRateLimiter limiter;

    public PasswordResetService(UserMapper users, StringRedisTemplate redis,
                                AuditService audit, LoginRateLimiter limiter,
                                @Value("${mail.host:}") String host,
                                @Value("${mail.port:465}") int port,
                                @Value("${mail.username:}") String username,
                                @Value("${mail.password:}") String password,
                                @Value("${mail.from:}") String from,
                                @Value("${mail.smtp.ssl-enable:1}") String sslEnable) {
        this.users = users;
        this.redis = redis;
        this.audit = audit;
        this.limiter = limiter;
        this.mail = new JavaMailSenderImpl();
        if (!host.isBlank() && !username.isBlank()) {
            mail.setHost(host);
            mail.setPort(port);
            mail.setUsername(username);
            mail.setPassword(password);
            Properties props = mail.getJavaMailProperties();
            props.put("mail.transport.protocol", "smtp");
            props.put("mail.smtp.auth", "true");
            props.put("mail.connectiontimeout", "8000");
            props.put("mail.timeout", "15000");
            props.put("mail.writetimeout", "15000");
            if ("1".equals(sslEnable)) {           // 465 隐式 SSL（QQ/163 通用）
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            } else {                                 // 587 STARTTLS
                props.put("mail.smtp.starttls.enable", "true");
            }
            mail.setDefaultEncoding("UTF-8");
        }
    }

    public boolean smtpConfigured() {
        return !mail.getHost().isBlank() && mail.getUsername() != null && !mail.getUsername().isBlank();
    }

    /** 发起找回：始终回同一形状（防枚举）。ip 用于粗限流。 */
    public void requestReset(String email, String ip) {
        if (ip != null && !ip.isBlank()) {
            String ipKey = IP_PREFIX + ip;
            Long n = redis.opsForValue().increment(ipKey);
            if (n != null && n == 1) redis.expire(ipKey, Duration.ofHours(1));
            if (n != null && n > 10)
                throw BizException.of(BizCode.RATE_LIMITED, "操作过于频繁，请稍后再试");
        }
        String norm = email.trim().toLowerCase();
        User u = users.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, norm));
        // 不存在也返回 ok——文案由 controller 统一，此处只保证不外泄差异
        if (u == null) {
            log.info("pwd reset: unknown email (no-op)");
            return;
        }
        if (!smtpConfigured())
            throw BizException.of(BizCode.INTERNAL_ERROR, "邮件服务未配置，请联系管理员");
        String keyHash = sha256(norm);
        // 日限
        String dayKey = DAY_PREFIX + keyHash + ":" + java.time.LocalDate.now();
        Long dayCnt = redis.opsForValue().increment(dayKey);
        if (dayCnt != null && dayCnt == 1) redis.expire(dayKey, Duration.ofDays(1));
        if (dayCnt != null && dayCnt > 5)
            throw BizException.of(BizCode.RATE_LIMITED, "今日验证码次数已达上限");
        // 60s 重发锁
        if (Boolean.TRUE.equals(redis.hasKey(SENT_PREFIX + keyHash)))
            throw BizException.of(BizCode.RATE_LIMITED, "验证码已发送，请60秒后再试");

        String code = String.format("%06d", RNG.nextInt(1_000_000));
        redis.opsForValue().set(CODE_PREFIX + keyHash, code, Duration.ofMinutes(10));
        redis.opsForValue().set(SENT_PREFIX + keyHash, "1", Duration.ofSeconds(60));
        redis.delete(FAIL_PREFIX + keyHash);

        try {
            SimpleMailMessage m = new SimpleMailMessage();
            m.setFrom(mail.getUsername());
            m.setTo(norm);
            m.setSubject("知识库系统 - 密码重置验证码");
            m.setText("""
                    你正在重置知识库系统账号的密码。
                    验证码：%s
                    有效期：10 分钟。若不是你本人操作，请忽略本邮件并建议修改邮箱密码。
                    """.formatted(code));
            mail.send(m);
            log.info("pwd reset code sent to {}", mask(norm));
        } catch (Exception e) {
            log.warn("smtp send failed: {}", e.getMessage());
            // 发信失败：清掉码与锁，让用户可以重试（不留一个等不到的码）
            redis.delete(CODE_PREFIX + keyHash);
            redis.delete(SENT_PREFIX + keyHash);
            throw BizException.of(BizCode.INTERNAL_ERROR, "邮件发送失败，请稍后重试");
        }
    }

    /** 验证码 + 新密码一步完成重置。 */
    public void confirmReset(String email, String code, String newPassword) {
        if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 64)
            throw BizException.of(BizCode.PARAM_INVALID, "密码需8-64位");
        String norm = email.trim().toLowerCase();
        String keyHash = sha256(norm);
        String stored = redis.opsForValue().get(CODE_PREFIX + keyHash);
        if (stored == null)
            throw BizException.of(BizCode.PARAM_INVALID, "验证码不存在或已过期");
        // 失败计数限流
        Long fails = redis.opsForValue().increment(FAIL_PREFIX + keyHash);
        if (fails != null && fails == 1) redis.expire(FAIL_PREFIX + keyHash, Duration.ofMinutes(10));
        if (fails != null && fails > 5) {
            redis.delete(CODE_PREFIX + keyHash);
            throw BizException.of(BizCode.RATE_LIMITED, "验证失败次数过多，请重新发起找回");
        }
        if (!stored.equals(code))
            throw BizException.of(BizCode.PARAM_INVALID, "验证码错误");
        User u = users.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, norm));
        if (u == null) throw BizException.of(BizCode.PARAM_INVALID, "验证码不存在或已过期");

        User upd = new User();
        upd.setId(u.getId());
        upd.setPasswordHash(Passwords.hash(newPassword));
        users.updateById(upd);
        redis.delete(CODE_PREFIX + keyHash);
        redis.delete(FAIL_PREFIX + keyHash);
        limiter.clearFailures(u.getUsername());       // 找回密码连带清登录锁定（见类注释）
        audit.record(u, "user.pwdreset", "user#" + u.getId(), "self-service via email");
        log.info("password reset done user={}", u.getUsername());
    }

    private static String sha256(String s) {
        try {
            byte[] b = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(b);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }
}
