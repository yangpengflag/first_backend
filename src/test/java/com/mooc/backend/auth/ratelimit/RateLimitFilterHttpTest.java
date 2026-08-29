package com.mooc.backend.auth.ratelimit;

import com.mooc.backend.auth.api.RegisterRequest;
import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.auth.service.AuthService;
import com.mooc.backend.auth.service.TokenService;
import com.mooc.backend.auth.support.TestClockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证端点限流（Task 9.3 ~ 9.5）。
 *
 * <p>阈值：登录 10 次/IP/15min 且 5 次/(IP+email)/15min；
 * 注册 5 次/IP/1h；重发 10 次/IP/1h 且 3 次/(IP+email)/24h。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
@Transactional
class RateLimitFilterHttpTest {

    private static final String EMAIL = "heidi@example.com";
    private static final String PASSWORD = "Str0ng!Pass";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RateLimiter rateLimiter;
    @Autowired
    private AuthService authService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        // 限流器是单例且状态在内存中，测试间必须清空
        rateLimiter.reset();
    }

    private String loginBody(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    private void postLogin(String email) throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(email)));
    }

    // ---------- 9.3：IP 维度 ----------

    /** 每次换邮箱以绕过 (IP+email) 维度，从而精确命中 IP 维度上限。 */
    @Test
    void loginBeyondIpLimitReturns429() throws Exception {
        for (int i = 0; i < 10; i++) {
            postLogin("user" + i + "@example.com");
        }

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("user99@example.com")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    // ---------- 9.3：(IP + email) 维度 ----------

    /** 同一邮箱第 6 次即被拦截，此时 IP 维度额度尚有余量。 */
    @Test
    void loginBeyondIpEmailLimitReturns429() throws Exception {
        for (int i = 0; i < 5; i++) {
            postLogin(EMAIL);
        }

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EMAIL)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    // ---------- 9.3：超限不产生业务副作用 ----------

    /** 超限请求不执行密码校验，故不计入 failedAttempts。 */
    @Test
    void rateLimitedLoginDoesNotIncrementFailedAttempts() throws Exception {
        authService.register(new RegisterRequest(EMAIL, PASSWORD, "Heidi"));
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        authService.verifyEmail(user.getVerificationCode());

        for (int i = 0; i < 5; i++) {
            postLogin(EMAIL);
        }
        int attemptsBeforeLimit = userRepository.findByEmail(EMAIL).orElseThrow().getFailedAttempts();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(EMAIL)))
                .andExpect(status().isTooManyRequests());

        assertThat(userRepository.findByEmail(EMAIL).orElseThrow().getFailedAttempts())
                .isEqualTo(attemptsBeforeLimit);
    }

    // ---------- 9.3：重发端点 ----------

    @Test
    void resendBeyondEmailLimitReturns429() throws Exception {
        String body = "{\"email\":\"" + EMAIL + "\"}";
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/auth/resend-verification")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body));
        }

        mockMvc.perform(post("/api/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    // ---------- 9.5：不影响已登录用户 ----------

    /** 限流只施加于认证端点；持令牌的常规请求不受影响。 */
    @Test
    void authenticatedRequestsAreNotRateLimited() throws Exception {
        authService.register(new RegisterRequest(EMAIL, PASSWORD, "Heidi"));
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        authService.verifyEmail(user.getVerificationCode());
        String token = tokenService.generateAccessToken(user.getId());

        for (int i = 0; i < 20; i++) {
            mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk());
        }
    }

    // ---------- 注册端点 ----------

    @Test
    void registerBeyondIpLimitReturns429() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"new" + i + "@example.com\",\"password\":\""
                            + PASSWORD + "\",\"displayName\":\"User\"}"));
        }

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new99@example.com\",\"password\":\""
                                + PASSWORD + "\",\"displayName\":\"User\"}"))
                .andExpect(status().isTooManyRequests());
    }

    // ---------- 5.6 / 5.7：密码重置端点限流 ----------

    /** 每次换邮箱以绕过 (IP+email) 维度，从而精确命中 IP 维度上限。 */
    @Test
    void forgotPasswordBeyondIpLimitReturns429() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/auth/forgot-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"reset" + i + "@example.com\"}"));
        }

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"reset99@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    /** 同一邮箱 3 次 / 24 小时——与 resend-verification 取齐。 */
    @Test
    void forgotPasswordBeyondEmailLimitReturns429() throws Exception {
        String body = "{\"email\":\"" + EMAIL + "\"}";

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/auth/forgot-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body));
        }

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void resetPasswordBeyondIpLimitReturns429() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/auth/reset-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"code-" + i + "\",\"newPassword\":\"" + PASSWORD + "\"}"));
        }

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"code-99\",\"newPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }
}
