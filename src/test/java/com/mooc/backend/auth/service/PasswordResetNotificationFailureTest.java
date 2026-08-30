package com.mooc.backend.auth.service;

import com.mooc.backend.auth.api.ForgotPasswordRequest;
import com.mooc.backend.auth.api.LoginRequest;
import com.mooc.backend.auth.api.RegisterRequest;
import com.mooc.backend.auth.api.ResetPasswordRequest;
import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.auth.support.MutableClock;
import com.mooc.backend.auth.support.TestClockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 密码变更通知失败不得回滚重置（Task 3.10）。
 *
 * <p>这是这类通知最容易踩的坑：邮件服务抖动就把密码重置给堵死，
 * 用户既拿不到新密码也登不上账号。故单独用一个「通知必抛异常」的
 * {@link MailSender} 实现来验证。
 */
@SpringBootTest
@Import({TestClockConfiguration.class, PasswordResetNotificationFailureTest.ThrowingMailConfig.class})
@Transactional
class PasswordResetNotificationFailureTest {

    private static final String EMAIL = "alice@example.com";
    private static final String PASSWORD = "Str0ng!Pass";
    private static final String NEW_PASSWORD = "N3w!Passw0rd";

    /** 重置邮件可正常投递，但「密码已变更」通知必定抛异常。 */
    @TestConfiguration
    static class ThrowingMailConfig {

        @Bean
        @Primary
        MailSender throwingMailSender() {
            return new MailSender() {
                @Override
                public void sendVerificationEmail(String toEmail, String verificationCode) {
                    // 不抛异常：注册与验证流程不受影响
                }

                @Override
                public void sendPasswordResetEmail(String toEmail, String resetCode) {
                    // 不抛异常：重置码投递必须成功，否则无从重置
                }

                @Override
                public void sendPasswordChangedNotice(String toEmail) {
                    throw new RuntimeException("SMTP unavailable");
                }
            };
        }
    }

    @Autowired
    private AuthService authService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordHasher passwordHasher;
    @Autowired
    private Clock clock;

    @BeforeEach
    void setUp() {
        ((MutableClock) clock).setNow(TestClockConfiguration.FIXED_NOW);
    }

    @Test
    void resetSucceedsEvenWhenNotificationThrows() {
        authService.register(new RegisterRequest(EMAIL, PASSWORD, "Alice"));
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        authService.verifyEmail(user.getVerificationCode());

        authService.requestPasswordReset(new ForgotPasswordRequest(EMAIL));
        String code = userRepository.findByEmail(EMAIL).orElseThrow().getPasswordResetCode();

        assertThatCode(() ->
                authService.resetPassword(new ResetPasswordRequest(code, NEW_PASSWORD)))
                .doesNotThrowAnyException();
    }

    /** 通知失败后，密码确实已更新且可用新密码登录。 */
    @Test
    void newPasswordIsEffectiveAfterNotificationFailure() {
        authService.register(new RegisterRequest(EMAIL, PASSWORD, "Alice"));
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        authService.verifyEmail(user.getVerificationCode());

        authService.requestPasswordReset(new ForgotPasswordRequest(EMAIL));
        String code = userRepository.findByEmail(EMAIL).orElseThrow().getPasswordResetCode();
        authService.resetPassword(new ResetPasswordRequest(code, NEW_PASSWORD));

        assertThat(passwordHasher.matches(NEW_PASSWORD,
                userRepository.findByEmail(EMAIL).orElseThrow().getPasswordHash())).isTrue();
        assertThat(authService.login(new LoginRequest(EMAIL, NEW_PASSWORD)).getAccessToken()).isNotBlank();
    }
}
