package com.mooc.backend.auth.service;

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
 * 软删除语义（Task 7.1 ~ 7.3）。
 *
 * <p>行保留、写入 deletedAt、邮箱唯一约束不释放、终态不可自行恢复。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
@Transactional
class AuthServiceDeletionTest {

    private static final Instant NOW = TestClockConfiguration.FIXED_NOW;
    private static final String PASSWORD = "Str0ng!Pass";
    private static final String EMAIL = "carol@example.com";

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
        ((MutableClock) clock).setNow(NOW);
    }

    private User registerAndActivate() {
        authService.register(new RegisterRequest(EMAIL, PASSWORD, "Carol"));
        return userRepository.findByEmail(EMAIL).orElseThrow();
    }

    private User activate(User user) {
        String code = user.getVerificationCode();
        authService.verifyEmail(code);
        return userRepository.findByEmail(EMAIL).orElseThrow();
    }

    private ErrorCode errorCodeOf(Throwable throwable) {
        return ((AuthException) throwable).getErrorCode();
    }

    // ---------- 7.1 / 7.2：软删除语义 ----------

    @Test
    void deletionIsSoftAndKeepsTheRow() {
        User user = activate(registerAndActivate());

        ((MutableClock) clock).advance(Duration.ofHours(1));
        authService.deleteAccount(user.getId());

        User deleted = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(deleted.getStatus()).isEqualTo(UserStatus.DELETED);
        assertThat(deleted.getDeletedAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(deleted.getId()).isEqualTo(user.getId());
        assertThat(deleted.getEmail()).isEqualTo(EMAIL);
        // 验证码随注销清除
        assertThat(deleted.getVerificationCode()).isNull();
    }

    @Test
    void deletedUserCannotLogInEvenWithCorrectPassword() {
        User user = activate(registerAndActivate());
        authService.deleteAccount(user.getId());

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOf(AuthException.class)
                .extracting(this::errorCodeOf)
                .isEqualTo(ErrorCode.ACCOUNT_DELETED);
    }

    // ---------- 7.3：邮箱不可重新注册 ----------

    @Test
    void deletedEmailCannotBeRegisteredAgain() {
        User user = activate(registerAndActivate());
        authService.deleteAccount(user.getId());

        assertThatThrownBy(() ->
                authService.register(new RegisterRequest(EMAIL, "An0ther!Pass", "Impostor")))
                .isInstanceOf(AuthException.class)
                .extracting(this::errorCodeOf)
                .isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED);
    }

    /** 已注销用户手中的 refresh token 同样失效。 */
    @Test
    void deletedUserCannotRefreshToken() {
        User user = activate(registerAndActivate());
        String refreshToken = tokenService.generateRefreshToken(user.getId());

        authService.deleteAccount(user.getId());

        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOf(AuthException.class)
                .extracting(this::errorCodeOf)
                .isEqualTo(ErrorCode.ACCOUNT_DELETED);
    }

    /** 注销后按邮箱仍能查到该行（软删除未物理移除）。 */
    @Test
    void userRowStillQueryableAfterDeletion() {
        User user = activate(registerAndActivate());
        authService.deleteAccount(user.getId());

        assertThat(userRepository.findByEmail(EMAIL)).isPresent();
        assertThat(userRepository.findById(user.getId())).isPresent();
    }
}
