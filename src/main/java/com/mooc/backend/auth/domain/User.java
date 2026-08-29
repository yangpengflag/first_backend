package com.mooc.backend.auth.domain;

import com.mooc.backend.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * 用户实体。
 *
 * <p><b>安全边界</b>：{@code passwordHash} / {@code salt} / {@code verificationCode}
 * 均为凭证类字段，<b>禁止</b>出现在任何 HTTP 响应中。出网一律经
 * {@code UserResponse.from(...)} 白名单转换。
 *
 * <p>表名用 {@code users} 而非 {@code user}，避免与 SQL 保留字冲突。
 */
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "uk_users_email", columnNames = "email"))
public class User extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String email;

    /** BCrypt 散列值（含内嵌 salt）。禁止出网。 */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /**
     * 预留字段：BCrypt 的 salt 内嵌于散列值中，故当前恒为 null。
     * 仅在未来切换到需独立 salt 的算法（如 PBKDF2）时使用。
     * 与 passwordHash / verificationCode 同受安全边界约束，禁止出网。
     */
    @Column(name = "salt")
    private String salt;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserStatus status;

    /** 一次性邮箱验证码（UUID v4）。禁止出网。 */
    @Column(name = "verification_code")
    private String verificationCode;

    @Column(name = "verification_code_expires_at")
    private Instant verificationCodeExpiresAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    /** 一次性密码重置码（UUID v4）。禁止出网。 */
    @Column(name = "password_reset_code")
    private String passwordResetCode;

    @Column(name = "password_reset_code_expires_at")
    private Instant passwordResetCodeExpiresAt;

    /**
     * 最近一次密码变更时刻。签发时刻早于该值的令牌一律判失效，
     * 用于在无状态 JWT 下实现「改密即登出」。
     */
    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    protected User() {
        // JPA only
    }

    private User(UUID id, String email, String passwordHash, String displayName, Instant now) {
        super(id, now);
        this.email = email;
        this.passwordHash = passwordHash;
        this.salt = null;
        this.displayName = displayName;
        this.avatarUrl = null;
        this.status = UserStatus.EMAIL_UNVERIFIED;
        this.failedAttempts = 0;
    }

    /**
     * 注册新用户：初始状态固定为 {@code EMAIL_UNVERIFIED}，不签发任何令牌。
     * 邮箱按小写归一化存储，保证唯一性判定与大小写无关。
     */
    public static User register(String rawEmail, String passwordHash, String displayName, Instant now) {
        return new User(UUID.randomUUID(), normalizeEmail(rawEmail), passwordHash, displayName, now);
    }

    public static String normalizeEmail(String rawEmail) {
        return rawEmail == null ? null : rawEmail.trim().toLowerCase(Locale.ROOT);
    }

    // ---------- 邮箱验证 ----------

    /** 签发一次性验证码，覆盖旧码。 */
    public void issueVerificationCode(String code, Instant now, Duration ttl) {
        this.verificationCode = code;
        this.verificationCodeExpiresAt = now.plus(ttl);
        this.touch(now);
    }

    /**
     * 校验并消费验证码：成功则激活账号并立即焚毁该码。
     * 无效 / 已过期 / 已使用 统一返回 false，不区分具体原因。
     */
    public boolean consumeVerificationCode(String code, Instant now) {
        if (code == null || this.verificationCode == null || this.verificationCodeExpiresAt == null) {
            return false;
        }
        if (now.isAfter(this.verificationCodeExpiresAt)) {
            return false;
        }
        if (!this.verificationCode.equals(code)) {
            return false;
        }
        this.verificationCode = null;
        this.verificationCodeExpiresAt = null;
        this.status = UserStatus.ACTIVE;
        this.failedAttempts = 0;
        this.touch(now);
        return true;
    }

    public boolean isVerificationPending(Instant now) {
        return this.verificationCode != null
                && this.verificationCodeExpiresAt != null
                && !now.isAfter(this.verificationCodeExpiresAt);
    }

    // ---------- 登录失败与锁定 ----------

    /** 处于锁定状态且锁定期未届满。 */
    public boolean isLocked(Instant now) {
        return this.status == UserStatus.LOCKED
                && this.lockedUntil != null
                && now.isBefore(this.lockedUntil);
    }

    /**
     * 记录一次登录失败。已达阈值的锁定用户不再递增计数。
     * 计数达到 maxAttempts 时自动转入 {@code LOCKED}。
     */
    public void recordFailedAttempt(Instant now, int maxAttempts, Duration lockDuration) {
        if (isLocked(now)) {
            return;
        }
        this.failedAttempts += 1;
        if (this.failedAttempts >= maxAttempts) {
            this.status = UserStatus.LOCKED;
            this.lockedUntil = now.plus(lockDuration);
        }
        this.touch(now);
    }

    /** 锁定期届满后自动解锁。 */
    public void unlockIfExpired(Instant now) {
        if (this.status == UserStatus.LOCKED
                && this.lockedUntil != null
                && !now.isBefore(this.lockedUntil)) {
            this.status = UserStatus.ACTIVE;
            this.lockedUntil = null;
            this.failedAttempts = 0;
            this.touch(now);
        }
    }

    /** 登录成功后重置失败计数。 */
    public void recordSuccessfulLogin(Instant now) {
        this.failedAttempts = 0;
        this.lockedUntil = null;
        this.touch(now);
    }

    // ---------- 密码重置 ----------

    /** 签发一次性密码重置码，覆盖旧码。 */
    public void issuePasswordResetCode(String code, Instant now, Duration ttl) {
        this.passwordResetCode = code;
        this.passwordResetCodeExpiresAt = now.plus(ttl);
        this.touch(now);
    }

    /**
     * 校验并消费重置码：成功则立即焚毁该码。
     * 无效 / 已过期 / 已使用 统一返回 false，不区分具体原因。
     */
    public boolean consumePasswordResetCode(String code, Instant now) {
        if (code == null || this.passwordResetCode == null || this.passwordResetCodeExpiresAt == null) {
            return false;
        }
        if (now.isAfter(this.passwordResetCodeExpiresAt)) {
            return false;
        }
        if (!this.passwordResetCode.equals(code)) {
            return false;
        }
        this.passwordResetCode = null;
        this.passwordResetCodeExpiresAt = null;
        this.touch(now);
        return true;
    }

    /**
     * 变更密码：更新散列、写入变更时刻（使此前签发的令牌失效）、焚毁重置码。
     *
     * <p>重置行为本身即证明邮箱可达与归属本人，故一并解除锁定并激活未验证账号——
     * 否则用户重置后仍被 423 / 403 挡在门外，属体验缺陷。
     * {@code DELETED} 状态不受影响。
     */
    public void changePassword(String newPasswordHash, Instant now) {
        this.passwordHash = newPasswordHash;
        this.passwordChangedAt = now;
        this.passwordResetCode = null;
        this.passwordResetCodeExpiresAt = null;
        this.failedAttempts = 0;
        this.lockedUntil = null;
        if (this.status == UserStatus.LOCKED || this.status == UserStatus.EMAIL_UNVERIFIED) {
            this.status = UserStatus.ACTIVE;
        }
        this.touch(now);
    }

    /**
     * 令牌签发时刻是否早于最近一次密码变更——早于则该令牌应当失效。
     * 用于阻断攻击者持旧令牌持续访问。
     */
    public boolean isTokenIssuedBeforePasswordChange(Instant tokenIssuedAt) {
        return this.passwordChangedAt != null
                && tokenIssuedAt != null
                && tokenIssuedAt.isBefore(this.passwordChangedAt);
    }

    // ---------- 软删除 ----------

    /**
     * 软删除：行保留、写入 deletedAt、清空验证码。
     * 邮箱唯一约束不释放，防止同一邮箱重复注册。
     */
    public void softDelete(Instant now) {
        this.status = UserStatus.DELETED;
        this.deletedAt = now;
        this.verificationCode = null;
        this.verificationCodeExpiresAt = null;
        this.touch(now);
    }

    // ---------- 访问器 ----------

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getSalt() {
        return salt;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public UserStatus getStatus() {
        return status;
    }

    public String getVerificationCode() {
        return verificationCode;
    }

    public Instant getVerificationCodeExpiresAt() {
        return verificationCodeExpiresAt;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public String getPasswordResetCode() {
        return passwordResetCode;
    }

    public Instant getPasswordResetCodeExpiresAt() {
        return passwordResetCodeExpiresAt;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
        this.touch(Instant.now());
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
        this.touch(Instant.now());
    }
}
