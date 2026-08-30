package com.mooc.backend.auth.service;

import com.mooc.backend.auth.api.AuthTokenResponse;
import com.mooc.backend.auth.api.LoginRequest;
import com.mooc.backend.auth.api.RegisterRequest;
import com.mooc.backend.auth.api.ResendVerificationRequest;
import com.mooc.backend.auth.api.UserResponse;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 邮箱验证与重发（Task 4.3 ~ 4.7）。
 *
 * <p>本类的 {@link #deadlockRegression_unverifiedUserRecoversWithoutEverLoggingIn()}
 * 是本 change 存在的理由：验证「EMAIL_UNVERIFIED → 403 → 无法登录」的死锁已被破解。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
@Transactional
class AuthServiceEmailVerificationTest {

    private static final Instant NOW = TestClockConfiguration.FIXED_NOW;
    private static final String PASSWORD = "Str0ng!Pass";
    private static final String EMAIL = "dave@example.com";

    @Autowired
    private AuthService authService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private LoggingMailSender mailSender;
    @Autowired
    private Clock clock;

    private String registerAndGetCode(String email) {
        authService.register(new RegisterRequest(email, PASSWORD, "Dave"));
        return userRepository.findByEmail(email).orElseThrow().getVerificationCode();
    }

    @BeforeEach
    void setUp() {
        mailSender.clear();
        ((MutableClock) clock).setNow(NOW);
    }

    // ---------- 4.3 / 4.4：验证激活 ----------

    @Test
    void validCodeActivatesAccountAndBurnsTheCode() {
        String code = registerAndGetCode(EMAIL);

        UserResponse response = authService.verifyEmail(code);

        assertThat(response.getStatus()).isEqualTo("ACTIVE");
        User saved = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(saved.getVerificationCode()).isNull();
        assertThat(saved.getVerificationCodeExpiresAt()).isNull();
    }

    @Test
    void unknownCodeIsRejected() {
        registerAndGetCode(EMAIL);

        assertThatThrownBy(() -> authService.verifyEmail("no-such-code"))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_VERIFICATION_CODE);
    }

    @Test
    void blankCodeIsRejected() {
        assertThatThrownBy(() -> authService.verifyEmail("   "))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_VERIFICATION_CODE);
    }

    @Test
    void expiredCodeIsRejected() {
        String code = registerAndGetCode(EMAIL);
        ((MutableClock) clock).advance(Duration.ofHours(25));

        assertThatThrownBy(() -> authService.verifyEmail(code))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_VERIFICATION_CODE);

        assertThat(userRepository.findByEmail(EMAIL).orElseThrow().getStatus())
                .isEqualTo(UserStatus.EMAIL_UNVERIFIED);
    }

    /** 用后即焚：同一 code 二次使用必须失败。 */
    @Test
    void codeCannotBeReused() {
        String code = registerAndGetCode(EMAIL);
        authService.verifyEmail(code);

        assertThatThrownBy(() -> authService.verifyEmail(code))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_VERIFICATION_CODE);
    }

    // ---------- 4.5 / 4.6：重发验证邮件 ----------

    @Test
    void resendIssuesFreshCodeForUnverifiedUser() {
        String originalCode = registerAndGetCode(EMAIL);
        mailSender.clear();

        authService.resendVerification(new ResendVerificationRequest(EMAIL));

        User saved = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(saved.getVerificationCode()).isNotBlank().isNotEqualTo(originalCode);
        assertThat(mailSender.getSentMails()).hasSize(1);
    }

    /** 恒定成功：不存在的邮箱同样「成功」，但不投递邮件。 */
    @Test
    void resendForUnknownEmailSucceedsSilently() {
        assertThatCode(() ->
                authService.resendVerification(new ResendVerificationRequest("nobody@example.com")))
                .doesNotThrowAnyException();

        assertThat(mailSender.getSentMails()).isEmpty();
    }

    /** 已激活用户不再收到验证邮件，但调用仍然成功（不泄露状态）。 */
    @Test
    void resendForAlreadyVerifiedUserSucceedsSilently() {
        String code = registerAndGetCode(EMAIL);
        authService.verifyEmail(code);
        mailSender.clear();

        assertThatCode(() -> authService.resendVerification(new ResendVerificationRequest(EMAIL)))
                .doesNotThrowAnyException();

        assertThat(mailSender.getSentMails()).isEmpty();
    }

    // ---------- 4.7：死锁回归（本 change 的核心价值） ----------

    /**
     * 完整走通「注册 → 登录被 403 → 免登录验证 → 登录成功」。
     * 若邮箱验证被设计成需要登录态，此测试将失败。
     */
    @Test
    void deadlockRegression_unverifiedUserRecoversWithoutEverLoggingIn() {
        String code = registerAndGetCode(EMAIL);

        // 1) 未验证用户登录 → 403，拿不到令牌
        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);

        // 2) 重发验证邮件（免鉴权，恒定成功）
        authService.resendVerification(new ResendVerificationRequest(EMAIL));
        String freshCode = userRepository.findByEmail(EMAIL).orElseThrow().getVerificationCode();

        // 3) 凭邮件链接中的 code 完成验证（免鉴权）
        assertThat(authService.verifyEmail(freshCode).getStatus()).isEqualTo("ACTIVE");

        // 4) 现已可正常登录
        AuthTokenResponse tokens = authService.login(new LoginRequest(EMAIL, PASSWORD));
        assertThat(tokens.getAccessToken()).isNotBlank();
        assertThat(tokens.getRefreshToken()).isNotBlank();
        assertThat(tokens.getUser().getStatus()).isEqualTo("ACTIVE");
    }
}
