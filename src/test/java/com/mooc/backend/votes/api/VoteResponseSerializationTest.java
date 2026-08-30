package com.mooc.backend.votes.api;

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
 * 响应安全边界回归护栏（votes spec「响应安全边界」）。
 *
 * <p>断言序列化输出键严格等于 {@link VoteResponse#WHITELISTED_FIELDS} / {@link VoteStatsResponse#WHITELISTED_FIELDS}，
 * 且不泄露 {@code deleted_at} 等审计字段。
 */
class VoteResponseSerializationTest {

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
    void voteResponseKeysExactlyMatchWhitelist() throws Exception {
        String json = mapper.writeValueAsString(VoteResponse.from(UUID.randomUUID(), "UP"));
        Set<String> names = new HashSet<>();
        mapper.readTree(json).fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrderElementsOf(VoteResponse.WHITELISTED_FIELDS);
    }

    @Test
    void statsResponseKeysExactlyMatchWhitelist() throws Exception {
        String json = mapper.writeValueAsString(VoteStatsResponse.from(UUID.randomUUID(), 3L, 1L, "DOWN"));
        Set<String> names = new HashSet<>();
        mapper.readTree(json).fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrderElementsOf(VoteStatsResponse.WHITELISTED_FIELDS);
    }

    @Test
    void neverLeaksSensitiveFields() throws Exception {
        String json = mapper.writeValueAsString(VoteResponse.from(UUID.randomUUID(), null));
        assertThat(json).doesNotContain("deleted").doesNotContain("deleted_at").doesNotContain("deletedAt");
    }
}
