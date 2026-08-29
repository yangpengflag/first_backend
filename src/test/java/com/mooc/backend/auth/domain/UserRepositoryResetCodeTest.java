package com.mooc.backend.auth.domain;

import com.mooc.backend.auth.support.TestClockConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 按密码重置码查找用户（Task 2.3 / 2.4）。 */
@SpringBootTest
@Import(TestClockConfiguration.class)
@Transactional
class UserRepositoryResetCodeTest {

    private static final Instant NOW = TestClockConfiguration.FIXED_NOW;

    @Autowired
    private UserRepository userRepository;

    private User userWithResetCode(String email, String code) {
        User user = User.register(email, "$2a$10$hashedvalue", "Alice", NOW);
        user.issuePasswordResetCode(code, NOW, Duration.ofHours(1));
        return userRepository.saveAndFlush(user);
    }

    @Test
    void findsUserByPasswordResetCode() {
        userWithResetCode("alice@example.com", "reset-123");

        assertThat(userRepository.findByPasswordResetCode("reset-123"))
                .isPresent()
                .get()
                .extracting(User::getEmail)
                .isEqualTo("alice@example.com");
    }

    /** 用后即焚：已消费的码被置空，不应再被检索到。 */
    @Test
    void consumedResetCodeIsNoLongerFindable() {
        User user = userWithResetCode("alice@example.com", "reset-123");

        assertThat(user.consumePasswordResetCode("reset-123", NOW)).isTrue();
        userRepository.saveAndFlush(user);

        assertThat(userRepository.findByPasswordResetCode("reset-123")).isEmpty();
    }

    @Test
    void unknownResetCodeReturnsEmpty() {
        userWithResetCode("alice@example.com", "reset-123");

        assertThat(userRepository.findByPasswordResetCode("no-such-code")).isEmpty();
    }

    @Test
    void verificationCodeAndResetCodeAreIndependent() {
        User user = userWithResetCode("alice@example.com", "reset-123");
        user.issueVerificationCode("verify-456", NOW, Duration.ofHours(24));
        userRepository.saveAndFlush(user);

        assertThat(userRepository.findByPasswordResetCode("reset-123")).isPresent();
        assertThat(userRepository.findByVerificationCode("verify-456")).isPresent();
        // 两类码互不串扰
        assertThat(userRepository.findByPasswordResetCode("verify-456")).isEmpty();
        assertThat(userRepository.findByVerificationCode("reset-123")).isEmpty();
    }
}
