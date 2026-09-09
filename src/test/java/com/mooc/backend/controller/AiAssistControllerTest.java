package com.mooc.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.mooc.backend.config.AiAssistProperties;
import com.mooc.backend.config.SecurityConfig;
import com.mooc.backend.dto.AiAssistRequest;
import com.mooc.backend.exception.AiAssistValidationException;
import com.mooc.backend.exception.AiChatRateLimitedException;
import com.mooc.backend.exception.AiChatUnavailableException;
import com.mooc.backend.filter.JwtAuthFilter;
import com.mooc.backend.filter.RateLimitFilter;
import com.mooc.backend.filter.UserStatusFilter;
import com.mooc.backend.service.AiAssistService;
import com.mooc.backend.service.AiAssistSuggestion;
import com.mooc.backend.service.RateLimiter;
import com.mooc.backend.service.TokenService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 写作辅助端点切片测试（tasks 3.1 / 3.2 / 3.3 / design.md D1 / D5 / D6）：真实 SecurityConfig 参与，
 * 验证登录鉴权、双维限流、统一 422/429/503 信封。service 用 {@code @MockitoBean} 桩替，零出网。
 *
 * <p>鉴权：本端点走 {@code anyRequest().authenticated()}，且项目用自定义 {@code JwtAuthFilter} 从 JWT
 * 取主体。测试中以 header {@code X-Test-User} 驱动被桩的 {@code JwtAuthFilter} 注入主体（避免依赖
 * 真实 JWT；同时避开 {@code SecurityContextPersistenceFilter} 对 {@code user()} 上下文的覆盖），
 * 无该 header 即为匿名 → 401。
 */
@WebMvcTest(AiAssistController.class)
@Import({SecurityConfig.class,
        AiAssistControllerTest.AiAssistPropsConfig.class, AiAssistControllerTest.FilterConfig.class})
class AiAssistControllerTest {

    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String AUTH_HEADER = "X-Test-User";
    private static final String VALID_TITLE_BODY =
            "{\"kind\":\"title\",\"content\":\"A long travel story about visiting China with friends.\"}";
    private static final String VALID_TAGS_BODY =
            "{\"kind\":\"tags\",\"content\":\"A long travel story about visiting China with friends.\"}";
    private static final String VALID_POLISH_BODY =
            "{\"kind\":\"polish\",\"content\":\"A long travel story about visiting China with friends.\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AiAssistService aiAssistService;

    @MockitoBean
    private RateLimiter rateLimiter;

    @TestConfiguration
    static class AiAssistPropsConfig {

        @Bean
        AiAssistProperties aiAssistProperties() {
            return new AiAssistProperties(12_000, 8_000, 200,
                    new AiAssistProperties.RateLimit(20, 10),
                    new AiAssistProperties.Timeout(5_000, 30_000));
        }
    }

    @TestConfiguration
    static class FilterConfig {

        @Bean
        JwtAuthFilter jwtAuthFilter() {
            JwtAuthFilter filter = org.mockito.Mockito.mock(JwtAuthFilter.class);
            // 仅在携带 X-Test-User 时注入主体；其余放行（匿名 → 401 由 SecurityConfig 兜底）。
            try {
                doAnswer(inv -> {
                    HttpServletRequest req = inv.getArgument(0);
                    HttpServletResponse res = inv.getArgument(1);
                    FilterChain chain = inv.getArgument(2);
                    String uid = req.getHeader(AUTH_HEADER);
                    if (uid != null) {
                        SecurityContextHolder.getContext().setAuthentication(
                                new UsernamePasswordAuthenticationToken(uid, null, List.of()));
                    }
                    try {
                        chain.doFilter(req, res);
                    } catch (jakarta.servlet.ServletException | java.io.IOException e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                }).when(filter).doFilter(any(), any(), any());
            } catch (jakarta.servlet.ServletException | java.io.IOException e) {
                throw new RuntimeException(e);
            }
            return filter;
        }

        @Bean
        RateLimitFilter rateLimitFilter(ObjectMapper objectMapper) {
            return new RateLimitFilter(org.mockito.Mockito.mock(RateLimiter.class), objectMapper,
                    org.mockito.Mockito.mock(com.mooc.backend.config.RateLimitProperties.class));
        }

        @Bean
        UserStatusFilter userStatusFilter(ObjectMapper objectMapper) {
            // 测试内鉴权由桩 JwtAuthFilter 注入主体即可，UserStatusFilter 走放行，
            // 避免 mock UserRepository 返回空导致已登录请求被误判 401。
            return new UserStatusFilter(
                    org.mockito.Mockito.mock(com.mooc.backend.repository.UserRepository.class),
                    objectMapper, java.time.Clock.systemUTC()) {
                @Override
                protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                                                jakarta.servlet.http.HttpServletResponse response,
                                                jakarta.servlet.FilterChain chain) {
                    try {
                        chain.doFilter(request, response);
                    } catch (jakarta.servlet.ServletException | java.io.IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            };
        }
    }

    private void allowRateLimit() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    }

    private static String bodyWithKindAndContent(String kind, String content) {
        return "{\"kind\":\"" + kind + "\",\"content\":\"" + content + "\"}";
    }

    // ---- 鉴权 ----

    @Test
    void unauthenticatedRequestIsRejectedWith401() throws Exception {
        mockMvc.perform(post("/api/ai/posts/assist")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_TITLE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    // ---- 校验 422 ----

    @Test
    void shortContentYields422Validation() throws Exception {
        allowRateLimit();
        mockMvc.perform(post("/api/ai/posts/assist")
                        .header(AUTH_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"title\",\"content\":\"too short\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void missingKindYields422Validation() throws Exception {
        allowRateLimit();
        mockMvc.perform(post("/api/ai/posts/assist")
                        .header(AUTH_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"A long travel story about visiting China with friends.\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void polishOverLimitYields422Validation() throws Exception {
        allowRateLimit();
        // > 8000 但 ≤ 12000：通过 DTO 校验，由 service 抛 AiAssistValidationException
        // （service 为 mock，此处桩其抛出等价异常，控制器测试只验「异常→422」映射；
        //  超长拒绝的真实逻辑由 AiAssistServiceTest 覆盖）。
        when(aiAssistService.suggest(any(), any(), any()))
                .thenThrow(new AiAssistValidationException(
                        "Polish content must be at most 8000 characters."));
        String body = bodyWithKindAndContent("polish", "x".repeat(9000));
        mockMvc.perform(post("/api/ai/posts/assist")
                        .header(AUTH_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ---- 限流 429（双维）----

    @Test
    void ipRateLimitedYields429BeforeModelCall() throws Exception {
        when(rateLimiter.tryAcquire(argThat(k -> k != null && k.contains("|ip|")), anyInt(), any(Duration.class)))
                .thenReturn(false);

        mockMvc.perform(post("/api/ai/posts/assist")
                        .header(AUTH_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_TITLE_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void userRateLimitedYields429BeforeModelCall() throws Exception {
        when(rateLimiter.tryAcquire(argThat(k -> k != null && k.contains("|ip|")), anyInt(), any(Duration.class)))
                .thenReturn(true);
        when(rateLimiter.tryAcquire(argThat(k -> k != null && k.contains("|user|")), anyInt(), any(Duration.class)))
                .thenReturn(false);

        mockMvc.perform(post("/api/ai/posts/assist")
                        .header(AUTH_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_TITLE_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    // ---- 未装配 503 ----

    @Test
    void unconfiguredModelYields503() throws Exception {
        allowRateLimit();
        when(aiAssistService.suggest(any(), any(), any()))
                .thenThrow(new AiChatUnavailableException());

        mockMvc.perform(post("/api/ai/posts/assist")
                        .header(AUTH_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_TITLE_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("AI_UNAVAILABLE"));
    }

    // ---- 成功 200 ----

    @Test
    void titleKindReturns200WithTitleOnly() throws Exception {
        allowRateLimit();
        when(aiAssistService.suggest(AiAssistRequest.Kind.title, null,
                "A long travel story about visiting China with friends."))
                .thenReturn(new AiAssistSuggestion("Great Wall Adventure", null, null));

        mockMvc.perform(post("/api/ai/posts/assist")
                        .header(AUTH_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_TITLE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("title"))
                .andExpect(jsonPath("$.title").value("Great Wall Adventure"))
                .andExpect(jsonPath("$.tags").doesNotExist())
                .andExpect(jsonPath("$.content").doesNotExist())
                .andExpect(jsonPath("$.request_id").exists());
    }

    @Test
    void tagsKindReturns200WithTagsOnly() throws Exception {
        allowRateLimit();
        when(aiAssistService.suggest(AiAssistRequest.Kind.tags, null,
                "A long travel story about visiting China with friends."))
                .thenReturn(new AiAssistSuggestion(null, List.of("hiking", "beijing"), null));

        mockMvc.perform(post("/api/ai/posts/assist")
                        .header(AUTH_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_TAGS_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("tags"))
                .andExpect(jsonPath("$.tags[0]").value("hiking"))
                .andExpect(jsonPath("$.title").doesNotExist())
                .andExpect(jsonPath("$.content").doesNotExist());
    }

    @Test
    void polishKindReturns200WithContentOnly() throws Exception {
        allowRateLimit();
        when(aiAssistService.suggest(AiAssistRequest.Kind.polish, null,
                "A long travel story about visiting China with friends."))
                .thenReturn(new AiAssistSuggestion(null, null, "Polished story."));

        mockMvc.perform(post("/api/ai/posts/assist")
                        .header(AUTH_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_POLISH_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("polish"))
                .andExpect(jsonPath("$.content").value("Polished story."))
                .andExpect(jsonPath("$.title").doesNotExist())
                .andExpect(jsonPath("$.tags").doesNotExist());
    }

    @Test
    void emptyResultReturns200WithNoFields() throws Exception {
        allowRateLimit();
        when(aiAssistService.suggest(AiAssistRequest.Kind.title, null,
                "A long travel story about visiting China with friends."))
                .thenReturn(new AiAssistSuggestion(null, null, null));

        mockMvc.perform(post("/api/ai/posts/assist")
                        .header(AUTH_HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_TITLE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("title"))
                .andExpect(jsonPath("$.title").doesNotExist())
                .andExpect(jsonPath("$.tags").doesNotExist())
                .andExpect(jsonPath("$.content").doesNotExist());
    }
}
