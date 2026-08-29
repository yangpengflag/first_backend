package com.mooc.backend.auth.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mooc.backend.auth.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>安全边界回归护栏</b>（openspec/specs/auth-module/spec.md「响应安全边界」）。
 *
 * <p>断言采用白名单策略：序列化输出的键集合必须<b>严格等于</b>白名单，
 * 而非「不包含黑名单」。因此新增实体字段默认封闭，必须显式加入
 * {@link UserResponse} 才会出网。
 */
class UserResponseSerializationTest {

    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void serializedKeysExactlyMatchWhitelist() throws Exception {
        String json = serializeFullyPopulatedUser();

        assertThat(fieldNamesOf(json))
                .containsExactlyInAnyOrderElementsOf(UserResponse.WHITELISTED_FIELDS);
    }

    @Test
    void neverLeaksCredentialFields() throws Exception {
        String json = serializeFullyPopulatedUser();

        // 同时覆盖 snake_case（DB 列名）与 camelCase（Java 字段名），
        // 防止 Jackson 命名策略变更导致护栏静默失效。
        assertThat(json).doesNotContain(
                "password_hash", "passwordHash",
                "salt",
                "verification_code", "verificationCode"
        );
    }

    /** 新增的密码重置字段同样受白名单保护，且无需改动护栏即自动封闭。 */
    @Test
    void neverLeaksPasswordResetFields() throws Exception {
        String json = serializeFullyPopulatedUser();

        assertThat(json).doesNotContain(
                "passwordResetCode", "password_reset_code",
                "passwordResetCodeExpiresAt", "password_reset_code_expires_at",
                "passwordChangedAt", "password_changed_at");
    }

    @Test
    void doesNotLeakLockoutOrDeletionInternals() throws Exception {
        String json = serializeFullyPopulatedUser();

        assertThat(json).doesNotContain(
                "failedAttempts", "failed_attempts",
                "lockedUntil", "locked_until",
                "deletedAt", "deleted_at",
                "updatedAt", "updated_at"
        );
    }

    @Test
    void mapsFieldsFromEntityCorrectly() {
        User user = fullyPopulatedUser();

        UserResponse response = UserResponse.from(user);

        assertThat(response.id()).isEqualTo(user.getId());
        assertThat(response.email()).isEqualTo("alice@example.com");
        assertThat(response.displayName()).isEqualTo("Alice");
        assertThat(response.status()).isEqualTo(UserStatusHolder.EMAIL_UNVERIFIED_NAME);
        assertThat(response.createdAt()).isEqualTo(NOW);
    }

    @Test
    void handlesNullUser() {
        assertThat(UserResponse.from(null)).isNull();
    }

    // ---------- helpers ----------

    /** 构造一个所有字段（含全部凭证类字段）均填满的实体。 */
    private User fullyPopulatedUser() {
        User user = User.register("Alice@Example.com", "$2a$10$abcdefghijklmnopqrstuvwxyz", "Alice", NOW);
        user.issueVerificationCode("verification-code-uuid-v4", NOW, Duration.ofHours(24));
        user.recordFailedAttempt(NOW, 5, Duration.ofMinutes(15));
        user.setAvatarUrl("https://cdn.example.com/avatar.png");
        user.issuePasswordResetCode("reset-code-uuid-v4", NOW, Duration.ofHours(1));
        // salt 恒为 null（BCrypt 内嵌）；passwordChangedAt 生产环境由重置流程写入。
        // 二者此处强制填充，以证明白名单对新增敏感字段同样封闭。
        ReflectionTestUtils.setField(user, "salt", "independent-salt-value");
        ReflectionTestUtils.setField(user, "passwordChangedAt", NOW.plus(Duration.ofHours(1)));
        return user;
    }

    private String serializeFullyPopulatedUser() throws Exception {
        return mapper.writeValueAsString(UserResponse.from(fullyPopulatedUser()));
    }

    private Set<String> fieldNamesOf(String json) throws Exception {
        JsonNode node = mapper.readTree(json);
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** 避免测试直接依赖枚举常量的辅助持有类。 */
    private static final class UserStatusHolder {
        static final String EMAIL_UNVERIFIED_NAME = "EMAIL_UNVERIFIED";
    }
}
