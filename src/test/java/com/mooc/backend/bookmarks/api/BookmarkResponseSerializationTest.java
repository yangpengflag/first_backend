package com.mooc.backend.bookmarks.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 响应安全边界回归护栏（bookmarks spec「响应安全边界」）。
 */
class BookmarkResponseSerializationTest {

    @BeforeEach
    void setUp() {
        MDC.put("requestId", "test-request-id");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void keysExactlyMatchWhitelist() throws Exception {
        String json = mapper.writeValueAsString(BookmarkResponse.from(UUID.randomUUID(), true));
        Set<String> names = new HashSet<>();
        mapper.readTree(json).fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrderElementsOf(BookmarkResponse.WHITELISTED_FIELDS);
    }

    @Test
    void neverLeaksSensitiveFields() throws Exception {
        String json = mapper.writeValueAsString(BookmarkResponse.from(UUID.randomUUID(), false));
        assertThat(json).doesNotContain("deleted").doesNotContain("deleted_at").doesNotContain("deletedAt");
    }
}
