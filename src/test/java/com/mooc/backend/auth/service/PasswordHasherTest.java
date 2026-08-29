package com.mooc.backend.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTest {

    /** 测试用低强度（4）以加速；生产由配置指定 strength=10。 */
    private final PasswordHasher hasher = new PasswordHasher(new BCryptPasswordEncoder(4));

    private static final String PASSWORD = "Str0ng!Pass";

    @Test
    void hashUsesBcryptFormat() {
        String hash = hasher.hash(PASSWORD);

        // BCrypt: $2a$ / $2b$ / $2y$ 前缀 + 强度 + 53 字符散列
        assertThat(hash).matches("^\\$2[aby]\\$\\d{2}\\$.+$");
        assertThat(hash).hasSize(60);
    }

    @Test
    void samePasswordProducesDifferentHashes() {
        String first = hasher.hash(PASSWORD);
        String second = hasher.hash(PASSWORD);

        // 每个散列使用独立随机 salt，故不相等
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void matchesAcceptsCorrectPassword() {
        String hash = hasher.hash(PASSWORD);
        assertThat(hasher.matches(PASSWORD, hash)).isTrue();
    }

    @Test
    void matchesRejectsWrongPassword() {
        String hash = hasher.hash(PASSWORD);
        assertThat(hasher.matches("Wr0ng!Pass", hash)).isFalse();
    }

    @Test
    void hashNeverContainsPlainPassword() {
        String hash = hasher.hash(PASSWORD);
        assertThat(hash).doesNotContain(PASSWORD);
    }

    @Test
    void matchesHandlesNullInputs() {
        String hash = hasher.hash(PASSWORD);
        assertThat(hasher.matches(null, hash)).isFalse();
        assertThat(hasher.matches(PASSWORD, null)).isFalse();
    }
}
