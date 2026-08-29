package com.mooc.backend.auth.service;

import com.mooc.backend.auth.api.ForgotPasswordRequest;
import com.mooc.backend.auth.api.RegisterRequest;
import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 邮件链接指向前端 + 密码长度上限（Task 5.1 / 5.3 / 5.4）。
 */
@SpringBootTest
@Import(com.mooc.backend.auth.support.TestClockConfiguration.class)
@Transactional
class MailLinkAndPasswordBoundsTest {

    private static final String EMAIL = "alice@example.com";
    private static final String PASSWORD = "Str0ng!Pass";
    private static final String FRONTEND = "http://localhost:3000";

    @Autowired
    private AuthService authService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private LoggingMailSender mailSender;
    @Autowired
    private Clock clock;

    @BeforeEach
    void setUp() {
        mailSender.clear();
    }

    // ---------- 5.3 / 5.4：邮件链接指向前端 ----------

    @Test
    void verificationEmailLinksToFrontendPage() {
        authService.register(new RegisterRequest(EMAIL, PASSWORD, "Alice"));

        LoggingMailSender.SentMail mail = mailSender.getSentMails().get(0);

        assertThat(mail.link()).startsWith(FRONTEND + "/auth/verify?code=");
        // 链接中携带的正是发给用户的一次性码
        assertThat(mail.link()).contains(mail.verificationCode());
        // 不得指向后端 API 地址
        assertThat(mail.link()).doesNotContain(":8080").doesNotContain("/api/");
    }

    @Test
    void passwordResetEmailLinksToFrontendPage() {
        authService.register(new RegisterRequest(EMAIL, PASSWORD, "Alice"));
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        authService.verifyEmail(user.getVerificationCode());
        mailSender.clear();

        authService.requestPasswordReset(new ForgotPasswordRequest(EMAIL));

        LoggingMailSender.SentMail mail = mailSender.getSentMails().get(0);

        assertThat(mail.link()).startsWith(FRONTEND + "/auth/reset-password?code=");
        assertThat(mail.link()).contains(mail.verificationCode());
        assertThat(mail.link()).doesNotContain(":8080").doesNotContain("/api/");
    }

    /** 密码变更通知不含任何链接或一次性码。 */
    @Test
    void passwordChangedNoticeCarriesNoCode() {
        authService.register(new RegisterRequest(EMAIL, PASSWORD, "Alice"));
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        authService.verifyEmail(user.getVerificationCode());

        authService.requestPasswordReset(new ForgotPasswordRequest(EMAIL));
        String code = userRepository.findByEmail(EMAIL).orElseThrow().getPasswordResetCode();
        mailSender.clear();

        authService.resetPassword(new com.mooc.backend.auth.api.ResetPasswordRequest(code, "N3w!Passw0rd"));

        LoggingMailSender.SentMail mail = mailSender.getSentMails().get(0);
        assertThat(mail.verificationCode()).isNull();
        assertThat(mail.link()).isNull();
    }

    // ---------- 5.1 / 5.2：密码长度上限 72 ----------

    @Test
    void registrationRejectsPasswordLongerThan72() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        String tooLong = "A1!a".repeat(30);   // 120 字符

        Set<ConstraintViolation<RegisterRequest>> violations =
                validator.validate(new RegisterRequest(EMAIL, tooLong, "Alice"));

        assertThat(violations).isNotEmpty();
    }

    @Test
    void registrationAcceptsPasswordWithinBounds() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        Set<ConstraintViolation<RegisterRequest>> violations =
                validator.validate(new RegisterRequest(EMAIL, "A1!a".repeat(18), "Alice"));  // 72 字符

        assertThat(violations).isEmpty();
    }

    @Test
    void registrationRejectsPasswordShorterThan8() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        Set<ConstraintViolation<RegisterRequest>> violations =
                validator.validate(new RegisterRequest(EMAIL, "Ab1!", "Alice"));

        assertThat(violations).isNotEmpty();
    }
}
