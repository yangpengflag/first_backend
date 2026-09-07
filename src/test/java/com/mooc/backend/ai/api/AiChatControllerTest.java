package com.mooc.backend.ai.api;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.mooc.backend.ai.config.AiChatProperties;
import com.mooc.backend.ai.service.AiChatService;
import com.mooc.backend.auth.ratelimit.RateLimitFilter;
import com.mooc.backend.auth.ratelimit.RateLimitProperties;
import com.mooc.backend.auth.ratelimit.RateLimiter;
import com.mooc.backend.auth.security.JwtAuthFilter;
import com.mooc.backend.auth.security.UserStatusFilter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import reactor.core.publisher.Flux;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 对话端点切片测试（tasks 4.1 / 5.2 / 5.3）：真实 SecurityConfig 参与，验证免登录放行
 * 与 MCP 路径 fail-closed；覆盖 422 / 429 / 503 / SSE 事件协议。
 */
@WebMvcTest(AiChatController.class)
@Import({com.mooc.backend.auth.config.SecurityConfig.class,
        AiChatControllerTest.AiChatPropsConfig.class, AiChatControllerTest.FilterConfig.class})
class AiChatControllerTest {

    private static final String VALID_BODY =
            "{\"sessionId\":\"11111111-1111-1111-1111-111111111111\",\"message\":\"Plan a trip\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiChatService aiChatService;

    @MockitoBean
    private RateLimiter rateLimiter;

    @TestConfiguration
    static class AiChatPropsConfig {

        @Bean
        AiChatProperties aiChatProperties() {
            return new AiChatProperties(true, 20, 30,
                    new AiChatProperties.RateLimit(10, 20));
        }
    }

    @TestConfiguration
    static class FilterConfig {

        @Bean
        RateLimitFilter rateLimitFilter(ObjectMapper objectMapper) {
            return new RateLimitFilter(org.mockito.Mockito.mock(RateLimiter.class), objectMapper,
                    org.mockito.Mockito.mock(RateLimitProperties.class));
        }

        @Bean
        JwtAuthFilter jwtAuthFilter() {
            return new JwtAuthFilter(
                    org.mockito.Mockito.mock(com.mooc.backend.auth.service.TokenService.class));
        }

        @Bean
        UserStatusFilter userStatusFilter(ObjectMapper objectMapper) {
            return new UserStatusFilter(
                    org.mockito.Mockito.mock(com.mooc.backend.auth.domain.UserRepository.class),
                    objectMapper, java.time.Clock.systemUTC());
        }
    }

    private void allowRateLimit() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    }

    @Test
    void unauthenticatedRequestReachesHandlerAndReturns503WhenUnconfigured() throws Exception {
        allowRateLimit();
        when(aiChatService.configured()).thenReturn(false);

        mockMvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("AI_UNAVAILABLE"));

        verify(aiChatService, never()).stream(anyString(), anyString());
    }

    @Test
    void invalidBodyYields422ValidationEnvelope() throws Exception {
        allowRateLimit();

        mockMvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"not-a-uuid\",\"message\":\"\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        verify(aiChatService, never()).stream(anyString(), anyString());
    }

    @Test
    void rateLimitedYields429BeforeAnyModelCall() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any(Duration.class)))
                .thenReturn(false);

        mockMvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

        verify(aiChatService, never()).stream(anyString(), anyString());
    }

    @Test
    void streamedChatEmitsDeltaEventsOverSSE() throws Exception {
        allowRateLimit();
        when(aiChatService.configured()).thenReturn(true);
        when(aiChatService.stream(eq("11111111-1111-1111-1111-111111111111"), eq("Plan a trip")))
                .thenReturn(Flux.just("Hel", "lo"));

        MvcResult mvcResult = mockMvc.perform(post("/api/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    if (!body.contains("event:delta") || !body.contains("Hel") || !body.contains("event:done")) {
                        throw new AssertionError("SSE body missing delta/done: " + body);
                    }
                });
    }

    @Test
    void mcpPathsStayFailClosedForAnonymousClients() throws Exception {
        mockMvc.perform(post("/mcp/message"))
                .andExpect(status().isUnauthorized());
    }
}
