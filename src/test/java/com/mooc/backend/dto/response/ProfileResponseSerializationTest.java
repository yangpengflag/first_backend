package com.mooc.backend.dto.response;
import com.mooc.backend.dto.response.ProfileResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mooc.backend.entity.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>档案安全边界回归护栏</b>（openspec/changes/add-user-profile/specs/user-profile/spec.md）。
 *
 * <p>断言采用白名单策略：序列化输出的键集合必须<b>严格等于</b>白名单，
 * 而非「不包含黑名单」。因此新增实体字段默认封闭，必须显式加入
 * {@link ProfileResponse} 才会出网。
 */
class ProfileResponseSerializationTest {

    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        MDC.put("requestId", "test-request-id");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void serializedKeysExactlyMatchWhitelist() throws Exception {
        String json = serializeFullyPopulatedUser();

        assertThat(fieldNamesOf(json))
                .containsExactlyInAnyOrderElementsOf(ProfileResponse.WHITELISTED_FIELDS);
    }

    @Test
    void neverLeaksCredentialFields() throws Exception {
        String json = serializeFullyPopulatedUser();

        // 凭证四字段：password_hash / salt / verification_code / password_reset_code。
        // 同时覆盖 snake_case（DB 列名）与 camelCase（Java 字段名），
        // 防止 Jackson 命名策略变更导致护栏静默失效。
        assertThat(json).doesNotContain(
                "password_hash", "passwordHash",
                "salt",
                "verification_code", "verificationCode",
                "password_reset_code", "passwordResetCode"
        );
    }

    @Test
    void neverLeaksLockoutOrDeletionInternals() throws Exception {
        String json = serializeFullyPopulatedUser();

        assertThat(json).doesNotContain(
                "failedAttempts", "failed_attempts",
                "lockedUntil", "locked_until",
                "deleted", "deletedAt", "deleted_at",
                "updatedAt", "updated_at",
                "password_changed_at", "passwordChangedAt"
        );
    }

    @Test
    void mapsFieldsFromEntityCorrectly() {
        User user = fullyPopulatedUser();

        ProfileResponse response = ProfileResponse.from(user);

        assertThat(response.getId()).isEqualTo(user.getId());
        assertThat(response.getEmail()).isEqualTo("alice@example.com");
        assertThat(response.getDisplayName()).isEqualTo("Alice");
        assertThat(response.getAvatarUrl()).isEqualTo("https://cdn.example.com/avatar.png");
        assertThat(response.getBio()).isEqualTo("Mountain lover.");
        assertThat(response.getTags()).containsExactly("Hiking", "Coffee");
        assertThat(response.getStatus()).isEqualTo("EMAIL_UNVERIFIED");
        assertThat(response.getRole()).isEqualTo("USER");
        assertThat(response.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void serializesUnsetBioAndTagsAsExplicitNull() throws Exception {
        User user = User.register("Alice@Example.com", "$2a$10$abcdefghijklmnopqrstuvwxyz", "Alice", NOW);

        String json = mapper.writeValueAsString(ProfileResponse.from(user));

        JsonNode node = mapper.readTree(json);
        assertThat(node.get("bio").isNull()).isTrue();
        assertThat(node.get("tags").isNull()).isTrue();
    }

    @Test
    void handlesNullUser() {
        assertThat(ProfileResponse.from(null)).isNull();
    }

    // ---------- helpers ----------

    /** 构造一个所有字段（含全部凭证类字段）均填满的实体。 */
    private User fullyPopulatedUser() {
        User user = User.register("Alice@Example.com", "$2a$10$abcdefghijklmnopqrstuvwxyz", "Alice", NOW);
        user.issueVerificationCode("verification-code-uuid-v4", NOW, Duration.ofHours(24));
        user.recordFailedAttempt(NOW, 5, Duration.ofMinutes(15));
        user.updateProfile(null, "https://cdn.example.com/avatar.png", "Mountain lover.",
                List.of("Hiking", "Coffee"), NOW);
        user.issuePasswordResetCode("reset-code-uuid-v4", NOW, Duration.ofHours(1));
        // salt 恒为 null（BCrypt 内嵌）；passwordChangedAt 生产环境由重置流程写入。
        // 二者此处强制填充，以证明白名单对新增敏感字段同样封闭。
        ReflectionTestUtils.setField(user, "salt", "independent-salt-value");
        ReflectionTestUtils.setField(user, "passwordChangedAt", NOW.plus(Duration.ofHours(1)));
        return user;
    }

    private String serializeFullyPopulatedUser() throws Exception {
        return mapper.writeValueAsString(ProfileResponse.from(fullyPopulatedUser()));
    }

    private Set<String> fieldNamesOf(String json) throws Exception {
        JsonNode node = mapper.readTree(json);
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
