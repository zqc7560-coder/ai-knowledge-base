package com.kb.security.login;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 登录限流：滑动窗口计数 + 连续失败锁定。
 * key 按 IP 与用户名双维度，防单点爆破。Lua 保原子。
 */
@Component
public class LoginRateLimiter {
    private static final String SCRIPT =
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1]) " +
            "local n = redis.call('ZCARD', KEYS[1]) " +
            "if n < tonumber(ARGV[4]) then " +
            "  redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3]) " +
            "  redis.call('EXPIRE', KEYS[1], ARGV[5]) " +
            "  return 0 " +
            "else return 1 end";

    private final StringRedisTemplate redis;

    public LoginRateLimiter(StringRedisTemplate redis) { this.redis = redis; }

    /** 每 IP 每分钟最多 5 次尝试。 */
    public boolean tryAcquireAttempt(String ip) {
        Long blocked = redis.execute(org.springframework.data.redis.core.script.RedisScript
                .of(SCRIPT, Long.class), List.of("login:ip:" + ip),
                String.valueOf(System.currentTimeMillis() - 60_000),
                String.valueOf(System.currentTimeMillis()), String.valueOf(System.nanoTime()),
                "5", "120");
        return blocked != null && blocked == 0;
    }

    /** 失败计数，15 分钟内同账号失败 ≥5 次锁 30 分钟。 */
    public void recordFailure(String username) {
        var ops = redis.opsForValue();
        String key = "login:fail:" + username;
        Long n = ops.increment(key);
        if (n != null && n == 1) redis.expire(key, Duration.ofMinutes(15));
        if (n != null && n >= 5) ops.set("login:lock:" + username, "1", Duration.ofMinutes(30));
    }

    public boolean locked(String username) {
        return Boolean.TRUE.equals(redis.hasKey("login:lock:" + username));
    }

    public void clearFailures(String username) {
        redis.delete("login:fail:" + username);
        redis.delete("login:lock:" + username);
    }
}
