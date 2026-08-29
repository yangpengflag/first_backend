package com.mooc.backend.auth.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 限流阈值，绑定 {@code auth.rate-limit.*}。
 *
 * <p>阈值取自 openspec/specs/auth-module/spec.md「认证端点限流」表。
 */
@ConfigurationProperties(prefix = "auth.rate-limit")
public class RateLimitProperties {

    /** 登录：单 IP 每 15 分钟。 */
    private int loginPerIpPer15m = 10;
    /** 登录：单 IP + 单邮箱 每 15 分钟（防定向爆破）。 */
    private int loginPerIpEmailPer15m = 5;
    /** 注册：单 IP 每小时。 */
    private int registerPerIpPerHour = 5;
    /** 重发验证邮件：单 IP 每小时。 */
    private int resendPerIpPerHour = 10;
    /** 重发验证邮件：单 IP + 单邮箱 每 24 小时。 */
    private int resendPerIpEmailPer24h = 3;

    /** 申请密码重置：单 IP 每小时（与 resend-verification 取齐）。 */
    private int forgotPasswordPerIpPerHour = 10;

    /** 申请密码重置：单 IP + 单邮箱 每 24 小时。 */
    private int forgotPasswordPerIpEmailPer24h = 3;

    /** 提交新密码：单 IP 每小时（防重置码爆破）。 */
    private int resetPasswordPerIpPerHour = 10;

    public int getLoginPerIpPer15m() {
        return loginPerIpPer15m;
    }

    public void setLoginPerIpPer15m(int loginPerIpPer15m) {
        this.loginPerIpPer15m = loginPerIpPer15m;
    }

    public int getLoginPerIpEmailPer15m() {
        return loginPerIpEmailPer15m;
    }

    public void setLoginPerIpEmailPer15m(int loginPerIpEmailPer15m) {
        this.loginPerIpEmailPer15m = loginPerIpEmailPer15m;
    }

    public int getRegisterPerIpPerHour() {
        return registerPerIpPerHour;
    }

    public void setRegisterPerIpPerHour(int registerPerIpPerHour) {
        this.registerPerIpPerHour = registerPerIpPerHour;
    }

    public int getResendPerIpPerHour() {
        return resendPerIpPerHour;
    }

    public void setResendPerIpPerHour(int resendPerIpPerHour) {
        this.resendPerIpPerHour = resendPerIpPerHour;
    }

    public int getResendPerIpEmailPer24h() {
        return resendPerIpEmailPer24h;
    }

    public void setResendPerIpEmailPer24h(int resendPerIpEmailPer24h) {
        this.resendPerIpEmailPer24h = resendPerIpEmailPer24h;
    }

    public int getForgotPasswordPerIpPerHour() {
        return forgotPasswordPerIpPerHour;
    }

    public void setForgotPasswordPerIpPerHour(int forgotPasswordPerIpPerHour) {
        this.forgotPasswordPerIpPerHour = forgotPasswordPerIpPerHour;
    }

    public int getForgotPasswordPerIpEmailPer24h() {
        return forgotPasswordPerIpEmailPer24h;
    }

    public void setForgotPasswordPerIpEmailPer24h(int forgotPasswordPerIpEmailPer24h) {
        this.forgotPasswordPerIpEmailPer24h = forgotPasswordPerIpEmailPer24h;
    }

    public int getResetPasswordPerIpPerHour() {
        return resetPasswordPerIpPerHour;
    }

    public void setResetPasswordPerIpPerHour(int resetPasswordPerIpPerHour) {
        this.resetPasswordPerIpPerHour = resetPasswordPerIpPerHour;
    }
}
