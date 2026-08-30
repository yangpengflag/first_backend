package com.mooc.backend.comments.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mooc.backend.comments.domain.Comment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 响应安全边界回归护栏（comments spec「响应安全边界」）。
 *
 * <p>断言白名单策略：序列化输出的键集合严格等于 {@link CommentResponse#WHITELISTED_FIELDS}，
 * 且不泄露 {@code deleted_at} / 作者 {@code email} 等字段。
 */
class CommentResponseSerializationTest {

    @BeforeEach
    void setUp() {
        MDC.put("requestId", "test-request-id");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");

    @Test
    void serializedKeysExactlyMatchWhitelist() throws Exception {
        Comment comment = Comment.create(UUID.randomUUID(), UUID.randomUUID(), "Nice!", null, NOW);
        String json = mapper.writeValueAsString(CommentResponse.from(comment, "Alice", "http://a", 2L));

        Set<String> names = new HashSet<>();
        JsonNode node = mapper.readTree(json);
        node.fieldNames().forEachRemaining(names::add);

        assertThat(names).containsExactlyInAnyOrderElementsOf(CommentResponse.WHITELISTED_FIELDS);
    }

    @Test
    void neverLeaksSensitiveFields() throws Exception {
        Comment comment = Comment.create(UUID.randomUUID(), UUID.randomUUID(), "Nice!", null, NOW);
        String json = mapper.writeValueAsString(CommentResponse.from(comment, "Alice", null, 0L));

        assertThat(json).doesNotContain("deleted").doesNotContain("deleted_at").doesNotContain("deletedAt").doesNotContain("\"email\"");
    }
}
