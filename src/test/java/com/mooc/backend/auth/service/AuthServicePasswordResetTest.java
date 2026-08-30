package com.mooc.backend.auth.service;

import com.mooc.backend.auth.api.ForgotPasswordRequest;
import com.mooc.backend.auth.api.LoginRequest;
import com.mooc.backend.auth.api.RegisterRequest;
import com.mooc.backend.auth.api.ResetPasswordRequest;
import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.auth.domain.UserStatus;
import com.mooc.backend.auth.exception.AuthException;
import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.auth.support.MutableClock;
import com.mooc.backend.auth.support.TestClockConfiguration;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 密码重置（Task 3.1 ~ 3.9）。
 *
 * <p>覆盖：恒定 202 防枚举、一次性码语义、重置后的状态副作用、新密码约束、变更通知。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
@Transactional
class AuthServicePasswordResetTest {

    private static final String EMAIL = "alice@example.com";
    private static final String PASSWORD = "Str0ng!Pass";
    private static final String NEW_PASSWORD = "N3w!Passw0rd";

    @Autowired
    private AuthService authService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private LoggingMailSender mailSender;
    @Autowired
    private PasswordHasher passwordHasher;
    @Autowired
    private Clock clock;

    @BeforeEach
    void setUp() {
        mailSender.clear();
        ((MutableClock) clock).setNow(TestClockConfiguration.FIXED_NOW);
    }

    private User activate(String email) {
        authService.register(new RegisterRequest(email, PASSWORD, "Alice"));
        User user = userRepository.findByEmail(email).orElseThrow();
        authService.verifyEmail(user.getVerificationCode());
        return userRepository.findByEmail(email).orElseThrow();
    }

    private String requestResetAndGetCode(String email) {
        authService.requestPasswordReset(new ForgotPasswordRequest(email));
        return userRepository.findByEmail(email).orElseThrow().getPasswordResetCode();
    }

    // ---------- 3.1 / 3.2：申请重置恒定成功 ----------

    @Test
    void existingUserReceivesResetEmail() {
        activate(EMAIL);
        mailSender.clear();   // 注册环节已投递验证邮件，此处只统计重置邮件

        authService.requestPasswordReset(new ForgotPasswordRequest(EMAIL));

        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(user.getPasswordResetCode()).isNotBlank();
        assertThat(user.getPasswordResetCodeExpiresAt()).isAfter(clock.instant());
        assertThat(mailSender.getSentMails()).hasSize(1);
    }

    /** 不存在的邮箱：同样「成功」，但不投递邮件。 */
    @Test
    void unknownEmailSucceedsSilently() {
        assertThatCode(() ->
                authService.requestPasswordReset(new ForgotPasswordRequest("nobody@example.com")))
                .doesNotThrowAnyException();

        assertThat(mailSender.getSentMails()).isEmpty();
    }

    @Test
    void deletedUserDoesNotReceiveResetEmail() {
        User user = activate(EMAIL);
        user.softDelete(clock.instant());
        userRepository.saveAndFlush(user);
        mailSender.clear();   // 注册环节的验证邮件不计入

        assertThatCode(() -> authService.requestPasswordReset(new ForgotPasswordRequest(EMAIL)))
                .doesNotThrowAnyException();

        assertThat(mailSender.getSentMails()).isEmpty();
        assertThat(userRepository.findByEmail(EMAIL).orElseThrow().getPasswordResetCode()).isNull();
    }

    /** 重置码有效期 1 小时，短于邮箱验证码的 24 小时。 */
    @Test
    void resetCodeExpiresInOneHour() {
        activate(EMAIL);

        String code = requestResetAndGetCode(EMAIL);

        assertThat(userRepository.findByEmail(EMAIL).orElseThrow().getPasswordResetCodeExpiresAt())
                .isEqualTo(clock.instant().plus(Duration.ofHours(1)));
        assertThat(code).isNotBlank();
    }

    // ---------- 3.3 / 3.4：凭码重置 ----------

    @Test
    void validCodeResetsPassword() {
        activate(EMAIL);
        String code = requestResetAndGetCode(EMAIL);

        authService.resetPassword(new ResetPasswordRequest(code, NEW_PASSWORD));

        User updated = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(passwordHasher.matches(NEW_PASSWORD, updated.getPasswordHash())).isTrue();
        assertThat(passwordHasher.matches(PASSWORD, updated.getPasswordHash())).isFalse();
    }

    @Test
    void resetPasswordAllowsLoginWithNewPassword() {
        activate(EMAIL);
        String code = requestResetAndGetCode(EMAIL);

        authService.resetPassword(new ResetPasswordRequest(code, NEW_PASSWORD));

        assertThat(authService.login(new LoginRequest(EMAIL, NEW_PASSWORD)).getAccessToken()).isNotBlank();
    }

    @Test
    void unknownCodeIsRejected() {
        activate(EMAIL);

        assertThatThrownBy(() ->
                authService.resetPassword(new ResetPasswordRequest("no-such-code", NEW_PASSWORD)))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_RESET_CODE);
    }

    @Test
    void expiredCodeIsRejected() {
        activate(EMAIL);
        String code = requestResetAndGetCode(EMAIL);

        ((MutableClock) clock).advance(Duration.ofHours(2));

        assertThatThrownBy(() ->
                authService.resetPassword(new ResetPasswordRequest(code, NEW_PASSWORD)))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_RESET_CODE);
    }

    @Test
    void codeCannotBeReused() {
        activate(EMAIL);
        String code = requestResetAndGetCode(EMAIL);
        authService.resetPassword(new ResetPasswordRequest(code, NEW_PASSWORD));

        assertThatThrownBy(() ->
                authService.resetPassword(new ResetPasswordRequest(code, "An0ther!Pass")))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_RESET_CODE);
    }

    // ---------- 3.5 / 3.6 / 3.7：重置后的状态副作用 ----------

    @Test
    void resetUpdatesPasswordChangedAtAndBurnsTheCode() {
        activate(EMAIL);
        String code = requestResetAndGetCode(EMAIL);

        authService.resetPassword(new ResetPasswordRequest(code, NEW_PASSWORD));

        User updated = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(updated.getPasswordChangedAt()).isEqualTo(clock.instant());
        assertThat(updated.getPasswordResetCode()).isNull();
        assertThat(updated.getFailedAttempts()).isZero();
    }

    /** 重置即证明邮箱可达，故一并把未验证账号激活。 */
    @Test
    void resetActivatesUnverifiedAccount() {
        authService.register(new RegisterRequest(EMAIL, PASSWORD, "Alice"));
        assertThat(userRepository.findByEmail(EMAIL).orElseThrow().getStatus())
                .isEqualTo(UserStatus.EMAIL_UNVERIFIED);

        String code = requestResetAndGetCode(EMAIL);
        authService.resetPassword(new ResetPasswordRequest(code, NEW_PASSWORD));

        assertThat(userRepository.findByEmail(EMAIL).orElseThrow().getStatus())
                .isEqualTo(UserStatus.ACTIVE);
    }

    /** 重置即解除可能的锁定，否则用户重置后仍被 423 挡在门外。 */
    @Test
    void resetUnlocksLockedAccount() {
        User user = activate(EMAIL);
        for (int i = 0; i < 5; i++) {
            user.recordFailedAttempt(clock.instant(), 5, Duration.ofMinutes(15));
        }
        userRepository.saveAndFlush(user);
        assertThat(userRepository.findByEmail(EMAIL).orElseThrow().getStatus())
                .isEqualTo(UserStatus.LOCKED);

        String code = requestResetAndGetCode(EMAIL);
        authService.resetPassword(new ResetPasswordRequest(code, NEW_PASSWORD));

        User updated = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(updated.getLockedUntil()).isNull();
    }

    // ---------- 3.8：新密码约束 ----------

    @Test
    void tooShortPasswordFailsBeanValidation() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        Set<ConstraintViolation<ResetPasswordRequest>> violations =
                validator.validate(new ResetPasswordRequest("some-code", "Ab1!"));

        assertThat(violations).isNotEmpty();
    }

    @Test
    void tooLongPasswordFailsBeanValidation() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        String tooLong = "A1!a".repeat(30);   // 120 字符 > 72 上限

        Set<ConstraintViolation<ResetPasswordRequest>> violations =
                validator.validate(new ResetPasswordRequest("some-code", tooLong));

        assertThat(violations).isNotEmpty();
    }

    // ---------- 3.9：密码变更通知 ----------

    @Test
    void resetSendsPasswordChangedNotice() {
        activate(EMAIL);
        String code = requestResetAndGetCode(EMAIL);
        mailSender.clear();

        authService.resetPassword(new ResetPasswordRequest(code, NEW_PASSWORD));

        assertThat(mailSender.getSentMails()).hasSize(1);
        assertThat(mailSender.getSentMails().get(0).toEmail()).isEqualTo(EMAIL);
        // 通知类邮件不含任何一次性码
        assertThat(mailSender.getSentMails().get(0).verificationCode()).isNull();
    }
}
