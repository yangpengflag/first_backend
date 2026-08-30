package com.mooc.backend.auth.service;

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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 账号锁定策略（Task 6.1 ~ 6.5）。
 *
 * <p>阈值 MAX_FAILED_ATTEMPTS = 5，锁定时长 LOCK_DURATION_MINUTES = 15。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
@Transactional
class AuthServiceLockoutTest {

    private static final Instant NOW = TestClockConfiguration.FIXED_NOW;
    private static final String PASSWORD = "Str0ng!Pass";
    private static final String EMAIL = "frank@example.com";
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    @Autowired
    private AuthService authService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private Clock clock;

    @BeforeEach
    void setUp() {
        ((MutableClock) clock).setNow(NOW);
    }

    private void registerAndActivate() {
        authService.register(new RegisterRequest(EMAIL, PASSWORD, "Frank"));
        String code = userRepository.findByEmail(EMAIL).orElseThrow().getVerificationCode();
        authService.verifyEmail(code);
    }

    private User reload() {
        return userRepository.findByEmail(EMAIL).orElseThrow();
    }

    private int statusCodeOf(Throwable throwable) {
        return ((AuthException) throwable).getStatus().value();
    }

    private ErrorCode errorCodeOf(Throwable throwable) {
        return ((AuthException) throwable).getErrorCode();
    }

    // ---------- 6.1 / 6.2：达到阈值触发锁定 ----------

    @Test
    void accountLocksWhenFailedAttemptsReachThreshold() {
        registerAndActivate();

        for (int i = 1; i < MAX_ATTEMPTS; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "Wr0ng!Pass")))
                    .isInstanceOf(AuthException.class);
            assertThat(reload().getStatus()).isEqualTo(UserStatus.ACTIVE);
        }

        // 第 5 次失败：本次直接原因仍是「密码错误」→ 401 INVALID_CREDENTIALS，
        // 但计数达到阈值，状态同时转入 LOCKED。
        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "Wr0ng!Pass")))
                .isInstanceOf(AuthException.class)
                .extracting(this::errorCodeOf)
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        assertThat(reload().getStatus()).isEqualTo(UserStatus.LOCKED);
        assertThat(reload().getLockedUntil()).isEqualTo(NOW.plus(LOCK_DURATION));
        assertThat(reload().getFailedAttempts()).isEqualTo(MAX_ATTEMPTS);
    }

    /**
     * 触发锁定的那一次仍报 401；自<b>下一次</b>尝试起，
     * 无论密码对错一律转为 423（锁定判定先于密码校验）。
     */
    @Test
    void attemptsAfterLockReturn423RegardlessOfPassword() {
        registerAndActivate();
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "Wr0ng!Pass")))
                    .isInstanceOf(AuthException.class);
        }
        assertThat(reload().getStatus()).isEqualTo(UserStatus.LOCKED);

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "Wr0ng!Pass")))
                .isInstanceOf(AuthException.class)
                .extracting(this::errorCodeOf)
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOf(AuthException.class)
                .extracting(this::errorCodeOf)
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);
    }

    // ---------- 6.3：锁定期间拒绝，且不继续计数 ----------

    @Test
    void lockedAccountRejectsCorrectPasswordWith423() {
        registerAndActivate();
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "Wr0ng!Pass")))
                    .isInstanceOf(AuthException.class);
        }

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOf(AuthException.class)
                .extracting(this::statusCodeOf)
                .isEqualTo(423);
    }

    @Test
    void failedAttemptsDoNotIncrementWhileLocked() {
        registerAndActivate();
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "Wr0ng!Pass")))
                    .isInstanceOf(AuthException.class);
        }
        int attemptsAtLock = reload().getFailedAttempts();

        // 锁定期间的失败（含错误密码）不再递增计数
        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "Wr0ng!Pass")))
                .isInstanceOf(AuthException.class)
                .extracting(this::errorCodeOf)
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);

        assertThat(reload().getFailedAttempts()).isEqualTo(attemptsAtLock);
    }

    // ---------- 6.4 / 6.5：自动解锁 ----------

    @Test
    void lockExpiresAndAccountBecomesUsableAgain() {
        registerAndActivate();
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "Wr0ng!Pass")))
                    .isInstanceOf(AuthException.class);
        }
        assertThat(reload().getStatus()).isEqualTo(UserStatus.LOCKED);

        ((MutableClock) clock).advance(Duration.ofMinutes(16));

        AuthTokenResponse response = authService.login(new LoginRequest(EMAIL, PASSWORD));

        assertThat(response.getAccessToken()).isNotBlank();
        User unlocked = reload();
        assertThat(unlocked.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(unlocked.getLockedUntil()).isNull();
        assertThat(unlocked.getFailedAttempts()).isZero();
    }

    @Test
    void stillLockedJustBeforeExpiry() {
        registerAndActivate();
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "Wr0ng!Pass")))
                    .isInstanceOf(AuthException.class);
        }

        ((MutableClock) clock).advance(Duration.ofMinutes(14));

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOf(AuthException.class)
                .extracting(this::statusCodeOf)
                .isEqualTo(423);
    }

    /**
     * 锁定响应附带重试等待秒数，供前端展示倒计时而非静态文案（Task 5.9）。
     *
     * <p>此处直接构造锁定状态而非连续失败登录——后者会先触发 (IP+email) 限流。
     */
    @Test
    void lockedLoginCarriesRetryAfterSeconds() {
        registerAndActivate();
        User user = reload();
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            user.recordFailedAttempt(NOW, MAX_ATTEMPTS, LOCK_DURATION);
        }
        userRepository.saveAndFlush(user);

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, PASSWORD)))
                .isInstanceOf(AuthException.class)
                .satisfies(thrown -> {
                    Object details = ((AuthException) thrown).getDetails();
                    assertThat(details).isInstanceOf(Map.class);
                    assertThat(((Map<?, ?>) details).keySet())
                            .anyMatch(key -> "retryAfterSeconds".equals(key));
                });
    }

    @Test
    void successfulLoginBeforeThresholdResetsCounter() {
        registerAndActivate();

        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "Wr0ng!Pass")))
                .isInstanceOf(AuthException.class);
        assertThatThrownBy(() -> authService.login(new LoginRequest(EMAIL, "Wr0ng!Pass")))
                .isInstanceOf(AuthException.class);
        assertThat(reload().getFailedAttempts()).isEqualTo(2);

        authService.login(new LoginRequest(EMAIL, PASSWORD));

        assertThat(reload().getFailedAttempts()).isZero();
    }
}
