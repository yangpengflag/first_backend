package com.mooc.backend.auth.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * 用户仓储。
 *
 * <p>注意：软删除的行仍保留在表中，因此 {@code findByEmail} 会命中
 * {@code DELETED} 用户——这是有意为之，用以保证「同邮箱不可重新注册」（409）。
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    /** 按已归一化的小写邮箱查找。 */
    Optional<User> findByEmail(String normalizedEmail);

    boolean existsByEmail(String normalizedEmail);

    /**
     * 按邮箱验证码查找。验证码为 UUID v4，全局唯一，可直接定位用户。
     * 已消费（焚毁）的码会被置空，故不会命中。
     */
    Optional<User> findByVerificationCode(String verificationCode);

    /**
     * 按密码重置码查找。重置码为 UUID v4 且用后即焚，
     * 故已消费的码不会命中。
     */
    Optional<User> findByPasswordResetCode(String passwordResetCode);
}
