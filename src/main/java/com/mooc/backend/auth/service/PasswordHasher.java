package com.mooc.backend.auth.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 密码散列的隔离封装，屏蔽底层 {@link PasswordEncoder} 实现细节。
 *
 * <p>默认使用 BCrypt（strength 由 {@code auth.bcrypt.strength} 配置，默认 10）。
 * BCrypt 的 salt 内嵌于散列值中，因此无需独立 salt 列。
 *
 * <p>系统任何环节均不得存储或输出明文密码。
 */
@Component
public class PasswordHasher {

    private final PasswordEncoder encoder;

    public PasswordHasher(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null) {
            return false;
        }
        return encoder.matches(rawPassword, storedHash);
    }
}
