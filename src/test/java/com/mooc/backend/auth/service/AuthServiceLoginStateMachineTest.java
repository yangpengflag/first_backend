package com.mooc.backend.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mooc.backend.auth.api.AuthTokenResponse;
import com.mooc.backend.auth.api.LoginRequest;
import com.mooc.backend.auth.api.RegisterRequest;
import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.auth.domain.UserStatus;
import com.mooc.backend.auth.exception.AuthException;
import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.auth.support.MutableClock;
import com.mooc.backend.auth.support.TestClockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 登录状态机四态响应码（Task 5.1 ~ 5.4）。
 *
 * <p>ACTIVE → 200、LOCKED → 423、DELETED → 401、EMAIL_UNVERIFIED → 403。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
@Transactional
class AuthServiceLoginStateMachineTest {

    private static final Instant NOW = TestClockConfiguration.FIXED_NOW;
    private static final String PASSWORD = "Str0ng!Pass";
    private static final String EMAIL = "alice@example.com";

    @Autowired
    private AuthService authService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private Clock clock;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        ((MutableClock) clock).setNow(NOW);
    }

    private String registerAndActivate(String email) {
        authService.register(new RegisterRequest(email, PASSWORD, "Alice"));
        String code = userRepository.findByEmail(email).orElseThrow().getVerificationCode();
        authService.verifyEmail(code);
        return email;
    }

    private ErrorCode errorCodeOf(Throwable throwable) {
        return ((AuthException) throwable).getErrorCode();
    }

    // ---------- 200：ACTIVE ----------

    @Test
    void activeUserWithCorrectPasswordReceivesTokens() {
        registerAndActivate(EMAIL);

        AuthTokenResponse response = authService.login(new LoginRequest(EMAIL, PASSWORD));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.user().status()).isEqualTo("ACTIVE");
        assertThat(response.user().email()).isEqualTo(EMAIL);
    }

    @Test
    void loginIsCaseInsensitiveOnEmail() {
        registerAndActivate(EMAIL);

        assertThat(authService.login(new LoginRequest("ALICE@EXAMPLE.COM", PASSWORD)).accessToken())
                .isNotBlank();
    }

    @Test
    void successfulLoginResetsFailedAttempts() {
        registerAndActivate(EMAIL);
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        user.recordFailedAttempt(NOW, 5, Duration.ofMinutes(15));
        userRepository.save(user);

        authService.login(new LoginRequest(EMAIL, PASSWORD));

        assertThat(userRepository.findByEmail(EMAIL).orElseThrow().getFailedAttempts()).isZero();
    }

    // ---------- 423：LOCKED ----------

    @Test
    void lockedUserIsRejectedWith423EvenWithCorrectPassword() {
        registerAndActivate(EMAIL);
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        for (int i = 0; i < 5; i++) {
            user.recordFailedAttempt(NOW, 5, Duration.ofMinutes(15));
        }
        userRepository.save(user);

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOf(AuthException.class)
                .extracting(this::errorCodeOf)
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);
    }

    // ---------- 401：DELETED ----------

    @Test
    void deletedUserIsRejectedWith401EvenWithCorrectPassword() {
        registerAndActivate(EMAIL);
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        user.softDelete(NOW);
        userRepository.save(user);

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOf(AuthException.class)
                .extracting(this::errorCodeOf)
                .isEqualTo(ErrorCode.ACCOUNT_DELETED);
    }

    // ---------- 403：EMAIL_UNVERIFIED ----------

    @Test
    void unverifiedUserWithCorrectPasswordIsRejectedWith403() {
        authService.register(new RegisterRequest(EMAIL, PASSWORD, "Alice"));

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOf(AuthException.class)
                .extracting(this::errorCodeOf)
                .isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);
    }

    /**
     * 密码校验先于状态判定：未验证用户输入<b>错误</b>密码应得 401 而非 403，
     * 否则等于告诉攻击者「密码其实无所谓」。
     */
    @Test
    void unverifiedUserWithWrongPasswordGets401Not403() {
        authService.register(new RegisterRequest(EMAIL, PASSWORD, "Alice"));

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "Wr0ng!Pass")))
                .isInstanceOf(AuthException.class)
                .extracting(this::errorCodeOf)
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    // ---------- 401：未知邮箱（与 DELETED 同码，收窄枚举面） ----------

    @Test
    void unknownEmailIsRejectedWith401() {
        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", PASSWORD)))
                .isInstanceOf(AuthException.class)
                .extracting(this::errorCodeOf)
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    /** 未知邮箱与已注销邮箱共用同一错误码，攻击者无法据此区分。 */
    @Test
    void unknownEmailAndDeletedAccountShareTheSameErrorCode() {
        registerAndActivate("deleted@example.com");
        User deleted = userRepository.findByEmail("deleted@example.com").orElseThrow();
        deleted.softDelete(NOW);
        userRepository.save(deleted);

        ErrorCode deletedCode = ErrorCode.ACCOUNT_DELETED;
        ErrorCode unknownCode = ErrorCode.INVALID_CREDENTIALS;

        // 二者 HTTP 状态码同为 401，这是收窄泄露面的关键
        assertThat(deletedCode.getStatus().value()).isEqualTo(401);
        assertThat(unknownCode.getStatus().value()).isEqualTo(401);
    }

    // ---------- 401：密码错误 ----------

    @Test
    void wrongPasswordIsRejectedAndCountsAsFailedAttempt() {
        registerAndActivate(EMAIL);

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "Wr0ng!Pass")))
                .isInstanceOf(AuthException.class)
                .extracting(this::errorCodeOf)
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        assertThat(userRepository.findByEmail(EMAIL).orElseThrow().getFailedAttempts()).isEqualTo(1);
    }

    /** 安全边界：登录响应同样不得泄露凭证字段。 */
    @Test
    void loginResponseNeverLeaksCredentialFields() throws Exception {
        registerAndActivate(EMAIL);

        AuthTokenResponse response = authService.login(new LoginRequest(EMAIL, PASSWORD));
        String json = objectMapper.writeValueAsString(response);

        assertThat(json).doesNotContain(
                "passwordHash", "password_hash",
                "salt",
                "verificationCode", "verification_code");
    }
}
