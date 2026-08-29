package com.mooc.backend.auth.service;

/**
 * 邮件投递抽象。
 *
 * <p>MVP 由 {@link LoggingMailSender} 以日志形式占位；接入真实 SMTP 时
 * 只需替换实现，不影响 spec 与调用方。
 */
public interface MailSender {

    /**
     * 投递邮箱验证邮件。
     *
     * @param toEmail          收件人（已归一化小写）
     * @param verificationCode 一次性验证码；实现类<b>不得</b>将其写入生产日志
     */
    void sendVerificationEmail(String toEmail, String verificationCode);

    /**
     * 投递密码重置邮件。
     *
     * @param toEmail   收件人（已归一化小写）
     * @param resetCode 一次性重置码；实现类<b>不得</b>将其写入生产日志
     */
    void sendPasswordResetEmail(String toEmail, String resetCode);

    /**
     * 投递「密码已变更」通知。
     *
     * <p>非本人发起重置时，这是用户唯一的察觉渠道。
     * 实现类抛出的异常由调用方捕获并仅记录日志——<b>通知失败不得回滚重置结果</b>。
     */
    void sendPasswordChangedNotice(String toEmail);
}
