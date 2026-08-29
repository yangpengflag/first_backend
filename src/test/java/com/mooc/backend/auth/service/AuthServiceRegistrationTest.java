package com.mooc.backend.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mooc.backend.auth.api.RegisterRequest;
import com.mooc.backend.auth.api.UserResponse;
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
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 注册流程（Task 4.1 / 4.2）。 */
@SpringBootTest
@Import(TestClockConfiguration.class)
@Transactional
class AuthServiceRegistrationTest {

    private static final Instant NOW = TestClockConfiguration.FIXED_NOW;
    private static final String PASSWORD = "Str0ng!Pass";

    @Autowired
    private AuthService authService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private LoggingMailSender mailSender;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private Clock clock;

    @BeforeEach
    void setUp() {
        mailSender.clear();
        ((MutableClock) clock).setNow(NOW);
    }

    @Test
    void registerCreatesUnverifiedUserAndSendsEmail() {
        UserResponse response = authService.register(
                new RegisterRequest("Alice@Example.com", PASSWORD, "Alice"));

        assertThat(response.status()).isEqualTo("EMAIL_UNVERIFIED");
        assertThat(response.email()).isEqualTo("alice@example.com");
        assertThat(response.displayName()).isEqualTo("Alice");
        assertThat(response.id()).isNotNull();
        assertThat(response.createdAt()).isEqualTo(NOW);
    }

    @Test
    void registerPersistsHashedPasswordAndFreshVerificationCode() {
        authService.register(new RegisterRequest("alice@example.com", PASSWORD, "Alice"));

        User saved = userRepository.findByEmail("alice@example.com").orElseThrow();

        assertThat(saved.getStatus()).isEqualTo(UserStatus.EMAIL_UNVERIFIED);
        assertThat(saved.getPasswordHash()).startsWith("$2");
        assertThat(saved.getPasswordHash()).doesNotContain(PASSWORD);
        assertThat(saved.getVerificationCode()).isNotBlank();
        assertThat(saved.getVerificationCodeExpiresAt()).isAfter(NOW);
        assertThat(saved.getFailedAttempts()).isZero();
        assertThat(saved.getDeletedAt()).isNull();
    }

    @Test
    void registerDeliversExactlyOneVerificationEmail() {
        authService.register(new RegisterRequest("alice@example.com", PASSWORD, "Alice"));

        assertThat(mailSender.getSentMails()).hasSize(1);
        assertThat(mailSender.getSentMails().get(0).toEmail()).isEqualTo("alice@example.com");
        assertThat(mailSender.getSentMails().get(0).verificationCode()).isNotBlank();
    }

    /** 安全边界：注册响应不得出现任何凭证类字段。 */
    @Test
    void registerResponseNeverLeaksCredentialFields() throws Exception {
        UserResponse response = authService.register(
                new RegisterRequest("alice@example.com", PASSWORD, "Alice"));

        String json = objectMapper.writeValueAsString(response);

        assertThat(json).doesNotContain(
                "passwordHash", "password_hash",
                "salt",
                "verificationCode", "verification_code");
    }

    @Test
    void duplicateEmailIsRejected() {
        authService.register(new RegisterRequest("alice@example.com", PASSWORD, "Alice"));

        assertThatThrownBy(() ->
                authService.register(new RegisterRequest("alice@example.com", PASSWORD, "Another")))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED);
    }

    @Test
    void duplicateEmailInDifferentCaseIsRejected() {
        authService.register(new RegisterRequest("alice@example.com", PASSWORD, "Alice"));

        assertThatThrownBy(() ->
                authService.register(new RegisterRequest("ALICE@EXAMPLE.COM", PASSWORD, "Another")))
                .isInstanceOf(AuthException.class)
                .extracting(ex -> ((AuthException) ex).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED);
    }

    @Test
    void duplicateRegistrationDoesNotCreateSecondRowNorSendEmail() {
        authService.register(new RegisterRequest("alice@example.com", PASSWORD, "Alice"));
        mailSender.clear();
        long before = userRepository.count();

        assertThatThrownBy(() ->
                authService.register(new RegisterRequest("alice@example.com", PASSWORD, "Another")))
                .isInstanceOf(AuthException.class);

        assertThat(userRepository.count()).isEqualTo(before);
        assertThat(mailSender.getSentMails()).isEmpty();
    }
}
