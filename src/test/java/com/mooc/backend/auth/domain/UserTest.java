package com.mooc.backend.auth.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import com.mooc.backend.auth.domain.Role;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private User newUser(String email) {
        return User.register(email, "$2a$10$hashedpasswordvalue", "Alice", NOW);
    }

    // ---------- 注册与归一化 ----------

    @Test
    void registerNormalizesEmailToLowerCase() {
        User user = newUser("Alice@Example.COM");
        assertThat(user.getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void registerTrimsSurroundingWhitespace() {
        User user = newUser("  alice@example.com  ");
        assertThat(user.getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void registerInitializesDefaults() {
        User user = newUser("alice@example.com");

        assertThat(user.getStatus()).isEqualTo(UserStatus.EMAIL_UNVERIFIED);
        assertThat(user.getRole()).isEqualTo(Role.USER);
        assertThat(user.getFailedAttempts()).isZero();
        assertThat(user.isDeleted()).isFalse();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getVerificationCode()).isNull();
        assertThat(user.getId()).isNotNull();
        assertThat(user.getCreatedAt()).isEqualTo(NOW);
        assertThat(user.getUpdatedAt()).isEqualTo(NOW);
    }

    /** BCrypt 的 salt 内嵌于散列值，故不设独立 salt。 */
    @Test
    void saltIsNullByDefaultBecauseBcryptEmbedsIt() {
        assertThat(newUser("alice@example.com").getSalt()).isNull();
    }

    // ---------- 邮箱验证码 ----------

    @Test
    void validCodeActivatesAccountAndBurnsTheCode() {
        User user = newUser("alice@example.com");
        user.issueVerificationCode("code-123", NOW, Duration.ofHours(24));

        assertThat(user.consumeVerificationCode("code-123", NOW)).isTrue();
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getVerificationCode()).isNull();
        assertThat(user.getVerificationCodeExpiresAt()).isNull();
    }

    @Test
    void codeCannotBeReused() {
        User user = newUser("alice@example.com");
        user.issueVerificationCode("code-123", NOW, Duration.ofHours(24));
        user.consumeVerificationCode("code-123", NOW);

        assertThat(user.consumeVerificationCode("code-123", NOW)).isFalse();
    }

    @Test
    void expiredCodeIsRejected() {
        User user = newUser("alice@example.com");
        user.issueVerificationCode("code-123", NOW, Duration.ofHours(24));

        Instant afterExpiry = NOW.plus(Duration.ofHours(25));
        assertThat(user.consumeVerificationCode("code-123", afterExpiry)).isFalse();
        assertThat(user.getStatus()).isEqualTo(UserStatus.EMAIL_UNVERIFIED);
    }

    @Test
    void wrongCodeIsRejected() {
        User user = newUser("alice@example.com");
        user.issueVerificationCode("code-123", NOW, Duration.ofHours(24));

        assertThat(user.consumeVerificationCode("wrong-code", NOW)).isFalse();
        assertThat(user.getStatus()).isEqualTo(UserStatus.EMAIL_UNVERIFIED);
    }

    // ---------- 失败计数与锁定 ----------

    @Test
    void accountLocksWhenFailedAttemptsReachThreshold() {
        User user = newUser("alice@example.com");

        for (int i = 1; i < MAX_ATTEMPTS; i++) {
            user.recordFailedAttempt(NOW, MAX_ATTEMPTS, LOCK_DURATION);
            assertThat(user.getStatus()).isEqualTo(UserStatus.EMAIL_UNVERIFIED);
        }

        user.recordFailedAttempt(NOW, MAX_ATTEMPTS, LOCK_DURATION);
        assertThat(user.getStatus()).isEqualTo(UserStatus.LOCKED);
        assertThat(user.getLockedUntil()).isEqualTo(NOW.plus(LOCK_DURATION));
        assertThat(user.getFailedAttempts()).isEqualTo(MAX_ATTEMPTS);
    }

    @Test
    void lockedAccountDoesNotIncrementFailedAttempts() {
        User user = newUser("alice@example.com");
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            user.recordFailedAttempt(NOW, MAX_ATTEMPTS, LOCK_DURATION);
        }
        int attemptsAtLock = user.getFailedAttempts();

        user.recordFailedAttempt(NOW, MAX_ATTEMPTS, LOCK_DURATION);
        assertThat(user.getFailedAttempts()).isEqualTo(attemptsAtLock);
    }

    @Test
    void isLockedRespectsLockWindow() {
        User user = newUser("alice@example.com");
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            user.recordFailedAttempt(NOW, MAX_ATTEMPTS, LOCK_DURATION);
        }

        assertThat(user.isLocked(NOW.plus(Duration.ofMinutes(1)))).isTrue();
        assertThat(user.isLocked(NOW.plus(Duration.ofMinutes(16)))).isFalse();
    }

    @Test
    void lockAutoExpiresAndResetsCounters() {
        User user = newUser("alice@example.com");
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            user.recordFailedAttempt(NOW, MAX_ATTEMPTS, LOCK_DURATION);
        }

        user.unlockIfExpired(NOW.plus(Duration.ofMinutes(16)));

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getFailedAttempts()).isZero();
    }

    @Test
    void unlockDoesNothingBeforeExpiry() {
        User user = newUser("alice@example.com");
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            user.recordFailedAttempt(NOW, MAX_ATTEMPTS, LOCK_DURATION);
        }

        user.unlockIfExpired(NOW.plus(Duration.ofMinutes(1)));

        assertThat(user.getStatus()).isEqualTo(UserStatus.LOCKED);
    }

    @Test
    void successfulLoginResetsFailedAttempts() {
        User user = newUser("alice@example.com");
        user.recordFailedAttempt(NOW, MAX_ATTEMPTS, LOCK_DURATION);
        user.recordFailedAttempt(NOW, MAX_ATTEMPTS, LOCK_DURATION);
        assertThat(user.getFailedAttempts()).isEqualTo(2);

        user.recordSuccessfulLogin(NOW);

        assertThat(user.getFailedAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }

    // ---------- 软删除 ----------

    @Test
    void softDeleteMarksStatusAndTimestampWithoutRemovingRow() {
        User user = newUser("alice@example.com");
        user.issueVerificationCode("code-123", NOW, Duration.ofHours(24));

        user.softDelete(NOW.plus(Duration.ofHours(1)));

        assertThat(user.getStatus()).isEqualTo(UserStatus.DELETED);
        assertThat(user.isDeleted()).isTrue();
        assertThat(user.getId()).isNotNull();
        assertThat(user.getEmail()).isEqualTo("alice@example.com");
        // 验证码随注销一并清除
        assertThat(user.getVerificationCode()).isNull();
    }

    // ---------- 相等性 ----------

    @Test
    void equalityIsBasedOnId() {
        User a = newUser("alice@example.com");
        User b = newUser("bob@example.com");

        assertThat(a).isEqualTo(a);
        assertThat(a).isNotEqualTo(b);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not a user");
    }

    // ---------- 密码重置 ----------

    @Test
    void passwordResetCodeIsSingleUse() {
        User user = newUser("alice@example.com");
        user.issuePasswordResetCode("reset-123", NOW, Duration.ofHours(1));

        assertThat(user.consumePasswordResetCode("reset-123", NOW)).isTrue();
        assertThat(user.getPasswordResetCode()).isNull();
        // 用后即焚：同一码不可重复使用
        assertThat(user.consumePasswordResetCode("reset-123", NOW)).isFalse();
    }

    @Test
    void expiredPasswordResetCodeIsRejected() {
        User user = newUser("alice@example.com");
        user.issuePasswordResetCode("reset-123", NOW, Duration.ofHours(1));

        assertThat(user.consumePasswordResetCode("reset-123", NOW.plus(Duration.ofHours(2)))).isFalse();
    }

    @Test
    void wrongPasswordResetCodeIsRejected() {
        User user = newUser("alice@example.com");
        user.issuePasswordResetCode("reset-123", NOW, Duration.ofHours(1));

        assertThat(user.consumePasswordResetCode("wrong-code", NOW)).isFalse();
        assertThat(user.getPasswordResetCode()).isEqualTo("reset-123");
    }

    @Test
    void changePasswordUpdatesTimestampAndBurnsResetCode() {
        User user = newUser("alice@example.com");
        user.issuePasswordResetCode("reset-123", NOW, Duration.ofHours(1));

        user.changePassword("$2a$10$newhashvalue", NOW.plus(Duration.ofMinutes(5)));

        assertThat(user.getPasswordHash()).isEqualTo("$2a$10$newhashvalue");
        assertThat(user.getPasswordChangedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(user.getPasswordResetCode()).isNull();
        assertThat(user.getFailedAttempts()).isZero();
    }

    /** 重置密码即证明归属本人，故一并解除锁定并激活未验证账号。 */
    @Test
    void changePasswordUnlocksAndActivates() {
        User locked = newUser("locked@example.com");
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            locked.recordFailedAttempt(NOW, MAX_ATTEMPTS, LOCK_DURATION);
        }
        assertThat(locked.getStatus()).isEqualTo(UserStatus.LOCKED);

        locked.changePassword("$2a$10$newhashvalue", NOW);

        assertThat(locked.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(locked.getLockedUntil()).isNull();

        User unverified = newUser("unverified@example.com");
        assertThat(unverified.getStatus()).isEqualTo(UserStatus.EMAIL_UNVERIFIED);

        unverified.changePassword("$2a$10$newhashvalue", NOW);

        assertThat(unverified.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void changePasswordLeavesDeletedAccountDeleted() {
        User user = newUser("alice@example.com");
        user.softDelete(NOW);

        user.changePassword("$2a$10$newhashvalue", NOW.plus(Duration.ofMinutes(1)));

        assertThat(user.getStatus()).isEqualTo(UserStatus.DELETED);
    }

    /** 令牌失效判定的核心：签发时刻早于密码变更即应作废。 */
    @Test
    void tokenIssuedBeforePasswordChangeIsDetected() {
        User user = newUser("alice@example.com");
        Instant tokenIssuedAt = NOW;

        user.changePassword("$2a$10$newhashvalue", NOW.plus(Duration.ofMinutes(10)));

        assertThat(user.isTokenIssuedBeforePasswordChange(tokenIssuedAt)).isTrue();
        assertThat(user.isTokenIssuedBeforePasswordChange(NOW.plus(Duration.ofMinutes(20)))).isFalse();
    }

    @Test
    void tokenCheckHandlesNeverChangedPassword() {
        User user = newUser("alice@example.com");

        assertThat(user.isTokenIssuedBeforePasswordChange(NOW)).isFalse();
        assertThat(user.isTokenIssuedBeforePasswordChange(null)).isFalse();
    }
}
