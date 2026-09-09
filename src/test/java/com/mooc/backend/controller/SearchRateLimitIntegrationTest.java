package com.mooc.backend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 搜索限流集成测试（change: ai-semantic-search，tasks 4.3）：
 * {@code app.search.rate-limit-per-minute=1} 时第二个请求 429 RATE_LIMITED。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.search.rate-limit-enabled=true",
        "app.search.rate-limit-per-minute=1"
})
class SearchRateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void secondRequestWithinWindowReturns429() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "lake"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/search").param("q", "lake"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }
}
