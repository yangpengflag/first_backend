package com.mooc.backend.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 认证模块可调参数，绑定 {@code auth.*}。
 *
 * <p>默认值与 openspec/specs/auth-module/spec.md 中的常量保持一致：
 * 验证码 24h、失败阈值 5 次、锁定 15 分钟。
 */
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    /** 邮箱验证码有效期（小时）。 */
    private int verificationCodeTtlHours = 24;

    /** 连续登录失败达到该次数即锁定账号。 */
    private int maxFailedAttempts = 5;

    /** 账号锁定时长（分钟）。 */
    private int lockDurationMinutes = 15;

    /**
     * 密码重置码有效期（小时）。
     * 刻意短于邮箱验证码（24 小时）——重置码可直接改密，敏感度更高。
     */
    private int passwordResetCodeTtlHours = 1;

    public Duration verificationCodeTtl() {
        return Duration.ofHours(verificationCodeTtlHours);
    }

    public Duration passwordResetCodeTtl() {
        return Duration.ofHours(passwordResetCodeTtlHours);
    }

    public Duration lockDuration() {
        return Duration.ofMinutes(lockDurationMinutes);
    }

    public int getVerificationCodeTtlHours() {
        return verificationCodeTtlHours;
    }

    public void setVerificationCodeTtlHours(int verificationCodeTtlHours) {
        this.verificationCodeTtlHours = verificationCodeTtlHours;
    }

    public int getMaxFailedAttempts() {
        return maxFailedAttempts;
    }

    public void setMaxFailedAttempts(int maxFailedAttempts) {
        this.maxFailedAttempts = maxFailedAttempts;
    }

    public int getLockDurationMinutes() {
        return lockDurationMinutes;
    }

    public void setLockDurationMinutes(int lockDurationMinutes) {
        this.lockDurationMinutes = lockDurationMinutes;
    }

    public int getPasswordResetCodeTtlHours() {
        return passwordResetCodeTtlHours;
    }

    public void setPasswordResetCodeTtlHours(int passwordResetCodeTtlHours) {
        this.passwordResetCodeTtlHours = passwordResetCodeTtlHours;
    }
}
