package com.mooc.backend.posts.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mooc.backend.posts.domain.Post;
import com.mooc.backend.posts.domain.PostStatus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 响应安全边界回归护栏（posts spec「响应安全边界」）。
 *
 * <p>断言白名单策略：序列化输出的键集合严格等于 {@link PostResponse#WHITELISTED_FIELDS}，
 * 且不泄露 {@code deleted_at} / 作者 {@code email} 等字段。
 */
class PostResponseSerializationTest {

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
        Post post = Post.create(UUID.randomUUID(), "T", "c", null,
                List.of("a"), PostStatus.PUBLISHED, NOW);
        String json = mapper.writeValueAsString(PostResponse.from(post, "Alice", "http://a", "summary"));

        Set<String> names = new HashSet<>();
        JsonNode node = mapper.readTree(json);
        node.fieldNames().forEachRemaining(names::add);

        assertThat(names).containsExactlyInAnyOrderElementsOf(PostResponse.WHITELISTED_FIELDS);
    }

    @Test
    void neverLeaksSensitiveFields() throws Exception {
        Post post = Post.create(UUID.randomUUID(), "T", "c", null,
                List.of(), PostStatus.PUBLISHED, NOW);
        String json = mapper.writeValueAsString(PostResponse.from(post, "Alice", null, "summary"));

        assertThat(json).doesNotContain("deleted_at").doesNotContain("deletedAt").doesNotContain("\"email\"");
    }
}
