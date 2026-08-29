package com.mooc.backend.auth.security;

import com.mooc.backend.auth.api.ForgotPasswordRequest;
import com.mooc.backend.auth.api.LoginRequest;
import com.mooc.backend.auth.api.RegisterRequest;
import com.mooc.backend.auth.api.ResetPasswordRequest;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 密码变更导致令牌失效（Task 4.1 ~ 4.5）。
 *
 * <p>推进时钟一律取 5 分钟：既晚于令牌签发，又早于 access token 的 15 分钟有效期，
 * 从而精确命中「失效」分支而非「过期」分支。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
@Transactional
class TokenInvalidationTest {

    private static final String EMAIL = "alice@example.com";
    private static final String PASSWORD = "Str0ng!Pass";
    private static final String NEW_PASSWORD = "N3w!Passw0rd";
    private static final Duration FORWARD = Duration.ofMinutes(5);

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

    private User activate() {
        authService.register(new RegisterRequest(EMAIL, PASSWORD, "Alice"));
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        authService.verifyEmail(user.getVerificationCode());
        return userRepository.findByEmail(EMAIL).orElseThrow();
    }

    /** 申请重置并立即完成，等价于「密码在此刻被变更」。 */
    private void changePasswordNow() {
        authService.requestPasswordReset(new ForgotPasswordRequest(EMAIL));
        String code = userRepository.findByEmail(EMAIL).orElseThrow().getPasswordResetCode();
        authService.resetPassword(new ResetPasswordRequest(code, NEW_PASSWORD));
    }

    // ---------- 4.1 / 4.2 ----------

    @Test
    void tokenIssuedBeforePasswordChangeIsRejected() throws Exception {
        User user = activate();
        String staleToken = tokenService.generateAccessToken(user.getId());

        ((MutableClock) clock).advance(FORWARD);
        changePasswordNow();

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + staleToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALIDATED"));
    }

    // ---------- 4.3 ----------

    @Test
    void tokenIssuedAfterPasswordChangeStillWorks() throws Exception {
        User user = activate();
        String staleToken = tokenService.generateAccessToken(user.getId());

        ((MutableClock) clock).advance(FORWARD);
        changePasswordNow();

        // 旧令牌已作废
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + staleToken))
                .andExpect(status().isUnauthorized());

        // 改密后重新签发的令牌正常
        String freshToken = authService.login(new LoginRequest(EMAIL, NEW_PASSWORD)).accessToken();

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + freshToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void tokenRemainsValidWithoutPasswordChange() throws Exception {
        User user = activate();
        String token = tokenService.generateAccessToken(user.getId());

        ((MutableClock) clock).advance(FORWARD);

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    // ---------- 4.4 / 4.5：refresh 端点同样受约束 ----------

    @Test
    void refreshTokenIssuedBeforePasswordChangeIsRejected() throws Exception {
        User user = activate();
        String staleRefreshToken = tokenService.generateRefreshToken(user.getId());

        ((MutableClock) clock).advance(FORWARD);
        changePasswordNow();

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + staleRefreshToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALIDATED"));
    }

    /** 旧 refresh token 不得成为绕过失效校验的后门。 */
    @Test
    void staleRefreshTokenCannotMintFreshAccessToken() throws Exception {
        User user = activate();
        String staleRefreshToken = tokenService.generateRefreshToken(user.getId());

        ((MutableClock) clock).advance(FORWARD);
        changePasswordNow();

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + staleRefreshToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }
}
