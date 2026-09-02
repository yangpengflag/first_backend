package com.mooc.backend.auth.service;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * 真实 SMTP 邮件实现（基于 Spring {@link JavaMailSender}）。
 *
 * <p>仅在配置了 {@code spring.mail.host} 时启用；否则由 {@link LoggingMailSender} 接管，
 * 因此开发 / 测试环境默认仍是日志实现，无需任何 SMTP 凭据，现有测试不受影响。
 *
 * <p>安全边界：验证码 / 重置码以链接形式出现在邮件正文中，属预期行为
 * （邮件本身就是为传递该链接而发）；但<b>不得</b>把码额外写入应用日志。
 */
@Component
@ConditionalOnProperty("spring.mail.host")
public class SmtpMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailSender.class);

    private static final String VERIFY_PATH = "/auth/verify?code=";
    private static final String RESET_PATH = "/auth/reset-password?code=";

    private final JavaMailSender mailSender;
    private final String frontendBaseUrl;
    private final String from;

    public SmtpMailSender(
            JavaMailSender mailSender,
            @Value("${app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl,
            @Value("${spring.mail.username:no-reply@wanderchina.app}") String from) {
        this.mailSender = mailSender;
        this.frontendBaseUrl = trimTrailingSlash(frontendBaseUrl);
        this.from = from;
        log.info("[MAIL] SmtpMailSender active, from={}", from);
    }

    @Override
    public void sendVerificationEmail(String toEmail, String verificationCode) {
        String link = link(VERIFY_PATH, verificationCode);
        send(toEmail, "验证你的 WanderChina 邮箱", verificationBody(link));
    }

    @Override
    public void sendPasswordResetEmail(String toEmail, String resetCode) {
        String link = link(RESET_PATH, resetCode);
        send(toEmail, "重置你的 WanderChina 密码", resetBody(link));
    }

    @Override
    public void sendPasswordChangedNotice(String toEmail) {
        send(toEmail, "你的 WanderChina 密码已变更", changedNoticeBody());
    }

    private void send(String to, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            log.debug("[MAIL] sent [{}] to {}", subject, to);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to send mail [" + subject + "] to " + to, ex);
        }
    }

    private String verificationBody(String link) {
        return """
            <div style="font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;max-width:480px;margin:0 auto;color:#1f2937">
              <h2 style="color:#0f766e">验证你的 WanderChina 邮箱</h2>
              <p>欢迎加入 WanderChina！请点击下面的按钮完成邮箱验证，验证后即可登录。</p>
              <p style="text-align:center;margin:28px 0">
                <a href="%s" style="background:#0f766e;color:#fff;text-decoration:none;padding:12px 28px;border-radius:8px;display:inline-block;font-weight:600">验证邮箱</a>
              </p>
              <p style="color:#6b7280;font-size:13px">若按钮无法点击，请复制以下链接到浏览器打开：<br><a href="%s" style="color:#0f766e;word-break:break-all">%s</a></p>
              <p style="color:#6b7280;font-size:13px">该链接 24 小时内有效。如果这不是你本人的操作，请忽略本邮件。</p>
            </div>
            """.formatted(link, link, link);
    }

    private String resetBody(String link) {
        return """
            <div style="font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;max-width:480px;margin:0 auto;color:#1f2937">
              <h2 style="color:#0f766e">重置你的 WanderChina 密码</h2>
              <p>我们收到了你的密码重置请求。请点击下面的按钮设置新密码。</p>
              <p style="text-align:center;margin:28px 0">
                <a href="%s" style="background:#0f766e;color:#fff;text-decoration:none;padding:12px 28px;border-radius:8px;display:inline-block;font-weight:600">重置密码</a>
              </p>
              <p style="color:#6b7280;font-size:13px">若按钮无法点击，请复制以下链接到浏览器打开：<br><a href="%s" style="color:#0f766e;word-break:break-all">%s</a></p>
              <p style="color:#6b7280;font-size:13px">该链接 24 小时内有效。如果这不是你本人的操作，请忽略本邮件，你的密码不会被更改。</p>
            </div>
            """.formatted(link, link, link);
    }

    private String changedNoticeBody() {
        return """
            <div style="font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;max-width:480px;margin:0 auto;color:#1f2937">
              <h2 style="color:#0f766e">你的 WanderChina 密码已变更</h2>
              <p>你的账户密码刚刚被成功修改。如果这是你本人的操作，无需任何处理。</p>
              <p style="color:#6b7280;font-size:13px">如果你没有进行过此操作，请立即重新发起密码重置并检查账号安全。</p>
            </div>
            """;
    }

    private String link(String path, String code) {
        return frontendBaseUrl + path + code;
    }

    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
