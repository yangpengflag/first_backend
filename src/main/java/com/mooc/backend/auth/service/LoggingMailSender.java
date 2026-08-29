package com.mooc.backend.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MVP 邮件实现：以日志代替真实投递。
 *
 * <p><b>安全约束</b>：验证码与重置码属一次性凭证，默认<b>不打日志</b>。
 * 仅当显式开启 {@code auth.mail.log-verification-code=true}（本地开发调试用）
 * 才会输出完整链接，默认关闭以防日志泄露凭证。
 *
 * <p>邮件中的链接一律指向<b>前端</b>页面，而非后端 API 地址——
 * 用户点击后由前端调用后端完成验证 / 重置，避免直接暴露后端地址与裸 JSON 响应。
 *
 * <p>内存中的发送记录供测试断言投递行为，不落盘、不进日志。
 */
@Component
public class LoggingMailSender implements MailSender {

    private static final String VERIFY_PATH = "/auth/verify?code=";
    private static final String RESET_PATH = "/auth/reset-password?code=";

    private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

    private final String frontendBaseUrl;
    private final boolean logVerificationCode;
    private final List<SentMail> sentMails = Collections.synchronizedList(new ArrayList<>());

    public LoggingMailSender(
            @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl,
            @Value("${auth.mail.log-verification-code:false}") boolean logVerificationCode) {
        this.frontendBaseUrl = trimTrailingSlash(frontendBaseUrl);
        this.logVerificationCode = logVerificationCode;
    }

    @Override
    public void sendVerificationEmail(String toEmail, String verificationCode) {
        String link = link(VERIFY_PATH, verificationCode);
        log.info("[MAIL] verification email sent to={} link={}", toEmail, mask(link, verificationCode));
        sentMails.add(new SentMail(toEmail, verificationCode, link, Instant.now()));
    }

    @Override
    public void sendPasswordResetEmail(String toEmail, String resetCode) {
        String link = link(RESET_PATH, resetCode);
        log.info("[MAIL] password reset email sent to={} link={}", toEmail, mask(link, resetCode));
        sentMails.add(new SentMail(toEmail, resetCode, link, Instant.now()));
    }

    @Override
    public void sendPasswordChangedNotice(String toEmail) {
        // 通知类邮件不含任何一次性码，无泄露面
        log.info("[MAIL] password changed notice sent to={}", toEmail);
        sentMails.add(new SentMail(toEmail, null, null, Instant.now()));
    }

    /** 投递历史（不可变快照），供测试断言。 */
    public List<SentMail> getSentMails() {
        return List.copyOf(sentMails);
    }

    public void clear() {
        sentMails.clear();
    }

    /**
     * @param toEmail          收件人
     * @param verificationCode 一次性码（验证码 / 重置码）；通知类邮件为 null
     * @param link             指向前端的完整链接；通知类邮件为 null
     * @param sentAt           投递时刻
     */
    public record SentMail(String toEmail, String verificationCode, String link, Instant sentAt) {
    }

    private String link(String path, String code) {
        return frontendBaseUrl + path + code;
    }

    /** 未开启调试时，把链接中的一次性码替换为掩码。 */
    private String mask(String link, String code) {
        if (logVerificationCode) {
            return link;
        }
        return link.replace(code, "****");
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
