package com.mooc.backend.auth.security;

import com.mooc.backend.auth.api.RegisterRequest;
import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.auth.service.AuthService;
import com.mooc.backend.auth.service.TokenService;
import com.mooc.backend.auth.support.MutableClock;
import com.mooc.backend.auth.support.TestClockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局状态拦截（Task 8.1 ~ 8.3）。
 *
 * <p>验证「令牌签名有效 ≠ 放行」：账号状态在两次请求之间发生变化时，
 * 下一次请求必须立即被拦截，无需等待令牌过期。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
@Transactional
class UserStatusFilterHttpTest {

    private static final String EMAIL = "grace@example.com";
    private static final String PASSWORD = "Str0ng!Pass";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthService authService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private Clock clock;

    @BeforeEach
    void setUp() {
        ((MutableClock) clock).setNow(TestClockConfiguration.FIXED_NOW);
    }

    /** 注册 + 激活，返回该用户的 access token。 */
    private String tokenForActivatedUser() {
        authService.register(new RegisterRequest(EMAIL, PASSWORD, "Grace"));
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        authService.verifyEmail(user.getVerificationCode());
        return tokenService.generateAccessToken(user.getId());
    }

    private User reload() {
        return userRepository.findByEmail(EMAIL).orElseThrow();
    }

    private void lockTheUser() {
        User user = reload();
        for (int i = 0; i < 5; i++) {
            user.recordFailedAttempt(clock.instant(), 5, Duration.ofMinutes(15));
        }
        userRepository.saveAndFlush(user);
    }

    @Test
    void activeUserCanAccessProtectedEndpoint() throws Exception {
        String token = tokenForActivatedUser();

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.email").value(EMAIL));
    }

    /** 登录后被锁定 → 下一次请求即 423，令牌虽未过期亦不放行。 */
    @Test
    void userLockedAfterLoginIsRejectedWith423() throws Exception {
        String token = tokenForActivatedUser();
        lockTheUser();

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().is(423))
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_LOCKED"));
    }

    /** 登录后被注销 → 下一次请求即 401。 */
    @Test
    void userDeletedAfterLoginIsRejectedWith401() throws Exception {
        String token = tokenForActivatedUser();

        User user = reload();
        user.softDelete(clock.instant());
        userRepository.saveAndFlush(user);

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_DELETED"));
    }

    @Test
    void missingTokenIsRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void malformedTokenIsRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void tokenForNonexistentUserIsRejectedWith401() throws Exception {
        String token = tokenService.generateAccessToken(UUID.randomUUID());

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    /**
     * 锁定期届满后自动解锁，用户可重新获得访问权。
     *
     * <p><b>值得注意的产品行为</b>：锁定时长（15 分钟）与 access token 有效期（15 分钟）相同，
     * 因此锁定期届满时旧令牌必然也已过期——用户需要重新登录。这是预期且更安全的行为，
     * 故此处验证「重新签发令牌后恢复访问」，而非「同一令牌复活」。
     */
    @Test
    void expiredLockAllowsUserToRegainAccess() throws Exception {
        String staleToken = tokenForActivatedUser();
        lockTheUser();

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + staleToken))
                .andExpect(status().is(423));

        ((MutableClock) clock).advance(Duration.ofMinutes(16));

        String renewedToken = tokenService.generateAccessToken(reload().getId());

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + renewedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(reload().getStatus().name()).isEqualTo("ACTIVE");
    }
}
