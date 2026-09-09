package com.mooc.backend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 搜索限流关闭路径（change: ai-semantic-search，tasks 4.3 补齐 review P2）：
 * {@code app.search.rate-limit-enabled=false} 时即使把单 IP 阈值调到 1，连续请求也全 200 不限流。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.search.rate-limit-enabled=false",
        "app.search.rate-limit-per-minute=1"
})
class SearchRateLimitDisabledIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void requestsPassThroughWhenRateLimitDisabled() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "lake")).andExpect(status().isOk());
        mockMvc.perform(get("/api/search").param("q", "lake")).andExpect(status().isOk());
        mockMvc.perform(get("/api/search").param("q", "lake")).andExpect(status().isOk());
    }
}
