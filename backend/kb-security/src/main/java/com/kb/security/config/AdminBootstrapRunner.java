package com.kb.security.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kb.domain.entity.User;
import com.kb.domain.mapper.UserMapper;
import com.kb.security.password.Passwords;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 管理员引导：系统首启时没有 ADMIN，后台（/api/admin/**）就永远没人能进——死锁。
 * 两种解法（本类都支持，由环境变量触发，默认关闭）：
 *   KB_ADMIN_USERNAME/PASSWORD：不存在则创建 ADMIN；已存在则提升为 ADMIN。
 *   未配置时什么都不做（生产建议首启后用 SQL 手工设角色，避免默认口令风险）。
 *
 * 幂等：每次启动都会把指定用户名确保为 ADMIN；密码只在"创建"时写入，
 * 已存在用户不会被重置（防误伤）。
 */
@Slf4j
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private final UserMapper users;
    private final String username;
    private final String password;

    public AdminBootstrapRunner(UserMapper users,
                                @Value("${KB_ADMIN_USERNAME:}") String username,
                                @Value("${KB_ADMIN_PASSWORD:}") String password) {
        this.users = users;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username == null || username.isBlank() || password == null || password.length() < 8) return;
        User exist = users.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (exist == null) {
            User admin = new User();
            admin.setUsername(username);
            admin.setEmail(username + "@kb.local");
            admin.setPasswordHash(Passwords.hash(password));
            admin.setRole("ADMIN");
            admin.setStatus(1);
            users.insert(admin);
            log.info("bootstrap: created ADMIN {}", username);
        } else if (!"ADMIN".equals(exist.getRole())) {
            exist.setRole("ADMIN");
            users.updateById(exist);
            log.info("bootstrap: promoted {} to ADMIN", username);
        }
    }
}
