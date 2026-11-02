package com.kb.security.password;

import org.springframework.security.crypto.bcrypt.BCrypt;

/** 仅用 BCrypt 类（不引整个 spring-security 配置栈）；cost=10 与 docs/01 §8 一致。 */
public final class Passwords {
    private Passwords() {}

    public static String hash(String raw) {
        return BCrypt.hashpw(raw, BCrypt.gensalt(10));
    }

    public static boolean matches(String raw, String hash) {
        try {
            return BCrypt.checkpw(raw, hash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
