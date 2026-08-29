package com.mooc.backend.auth.service;

import com.mooc.backend.auth.api.AuthTokenResponse;
import com.mooc.backend.auth.api.ForgotPasswordRequest;
import com.mooc.backend.auth.api.LoginRequest;
import com.mooc.backend.auth.api.RegisterRequest;
import com.mooc.backend.auth.api.ResendVerificationRequest;
import com.mooc.backend.auth.api.ResetPasswordRequest;
import com.mooc.backend.auth.api.UserResponse;
import com.mooc.backend.auth.config.AuthProperties;
import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.auth.domain.UserStatus;
import com.mooc.backend.auth.exception.AuthException;
import com.mooc.backend.auth.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 认证业务核心。
 *
 * <p><b>登录状态机判定顺序</b>（openspec/specs/auth-module/spec.md）：
 * <pre>
 *   1. 用户不存在              → 401 INVALID_CREDENTIALS
 *   2. DELETED                 → 401 ACCOUNT_DELETED
 *   3. LOCKED（未届满）        → 423 ACCOUNT_LOCKED
 *   4. LOCKED（已届满）        → 自动解锁并继续
 *   5. 密码错误                → 401 INVALID_CREDENTIALS（计数 +1，可触发锁定）
 *   6. EMAIL_UNVERIFIED        → 403 EMAIL_NOT_VERIFIED
 *   7. 成功                    → 200 + 令牌
 * </pre>
 * 步骤 5 先于 6，保证未验证用户输入错密码时得到 401 而非 403（不泄露邮箱状态）。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final TokenService tokenService;
    private final MailSender mailSender;
    private final Clock clock;
    private final AuthProperties properties;

    public AuthService(
            UserRepository userRepository,
            PasswordHasher passwordHasher,
            TokenService tokenService,
            MailSender mailSender,
            Clock clock,
            AuthProperties properties) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.tokenService = tokenService;
        this.mailSender = mailSender;
        this.clock = clock;
        this.properties = properties;
    }

    // ---------- 注册 ----------

    /**
     * 注册：创建 {@code EMAIL_UNVERIFIED} 用户并投递验证邮件。
     * <b>不签发任何令牌</b>——必须先完成邮箱验证。
     */
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = User.normalizeEmail(request.email());

        if (userRepository.existsByEmail(email)) {
            throw AuthException.emailAlreadyRegistered();
        }

        User user = User.register(email, passwordHasher.hash(request.password()),
                request.displayName(), now());
        user.issueVerificationCode(newVerificationCode(), now(), properties.verificationCodeTtl());

        User saved = userRepository.save(user);
        mailSender.sendVerificationEmail(email, saved.getVerificationCode());
        return UserResponse.from(saved);
    }

    // ---------- 邮箱验证（免鉴权，破解死锁） ----------

    /** 凭一次性验证码激活账号。无效 / 过期 / 已用 统一抛 400。 */
    @Transactional
    public UserResponse verifyEmail(String code) {
        if (code == null || code.isBlank()) {
            throw AuthException.invalidVerificationCode();
        }

        User user = userRepository.findByVerificationCode(code)
                .orElseThrow(AuthException::invalidVerificationCode);

        if (!user.consumeVerificationCode(code, now())) {
            throw AuthException.invalidVerificationCode();
        }

        return UserResponse.from(userRepository.save(user));
    }

    /**
     * 重发验证邮件。<b>恒定成功</b>：无论邮箱是否存在、是否已验证，
     * 均不抛异常也不返回差异信息，以防账号枚举与邮件轰炸。
     */
    @Transactional
    public void resendVerification(ResendVerificationRequest request) {
        String email = User.normalizeEmail(request.email());

        userRepository.findByEmail(email).ifPresent(user -> {
            if (user.getStatus() == UserStatus.EMAIL_UNVERIFIED) {
                user.issueVerificationCode(newVerificationCode(), now(), properties.verificationCodeTtl());
                userRepository.save(user);
                mailSender.sendVerificationEmail(email, user.getVerificationCode());
            }
        });
    }

    // ---------- 登录 ----------

    @Transactional
    public AuthTokenResponse login(LoginRequest request) {
        String email = User.normalizeEmail(request.email());

        User user = userRepository.findByEmail(email)
                .orElseThrow(AuthException::invalidCredentials);

        if (user.getStatus() == UserStatus.DELETED) {
            throw AuthException.accountDeleted();
        }
        if (user.isLocked(now())) {
            throw lockedWithRetryAfter(user);
        }
        user.unlockIfExpired(now());

        if (!passwordHasher.matches(request.password(), user.getPasswordHash())) {
            user.recordFailedAttempt(now(), properties.getMaxFailedAttempts(), properties.lockDuration());
            userRepository.save(user);
            throw AuthException.invalidCredentials();
        }

        if (user.getStatus() == UserStatus.EMAIL_UNVERIFIED) {
            userRepository.save(user);
            throw AuthException.emailNotVerified();
        }

        user.recordSuccessfulLogin(now());
        User saved = userRepository.save(user);

        return new AuthTokenResponse(
                tokenService.generateAccessToken(saved.getId()),
                tokenService.generateRefreshToken(saved.getId()),
                UserResponse.from(saved)
        );
    }

    // ---------- 令牌刷新 ----------

    /** 校验 refresh token 类型后签发新的 access token。 */
    @Transactional
    public AuthTokenResponse refresh(String refreshToken) {
        if (!tokenService.isValid(refreshToken)) {
            throw AuthException.unauthenticated();
        }
        if (!TokenService.TYPE_REFRESH.equals(tokenService.parseTokenType(refreshToken))) {
            throw AuthException.unauthenticated();
        }

        UUID userId = tokenService.parseUserId(refreshToken);
        User user = userRepository.findById(userId).orElseThrow(AuthException::unauthenticated);

        if (user.getStatus() == UserStatus.DELETED) {
            throw AuthException.accountDeleted();
        }
        if (user.isLocked(now())) {
            throw AuthException.accountLocked();
        }
        // refresh token 同样受密码变更约束：旧 refresh 不得换取新的 access token
        if (user.isTokenIssuedBeforePasswordChange(tokenService.parseIssuedAt(refreshToken))) {
            throw AuthException.tokenInvalidated();
        }

        return new AuthTokenResponse(
                tokenService.generateAccessToken(user.getId()),
                tokenService.generateRefreshToken(user.getId()),
                UserResponse.from(user)
        );
    }

    // ---------- 当前用户与注销 ----------

    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(UUID userId) {
        return UserResponse.from(userRepository.findById(userId)
                .orElseThrow(AuthException::unauthenticated));
    }

    /** 软删除账号：行保留，写入 deletedAt，邮箱唯一约束不释放。 */
    @Transactional
    public void deleteAccount(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(AuthException::unauthenticated);
        user.softDelete(now());
        userRepository.save(user);
    }

    // ---------- 密码重置 ----------

    /**
     * 申请密码重置。<b>恒定成功</b>：无论邮箱是否存在、是否已注销，
     * 均不抛异常也不返回差异信息，以防账号枚举与邮件轰炸。
     *
     * <p>仅当邮箱存在且状态非 {@code DELETED} 时才实际签发重置码并投递邮件。
     */
    @Transactional
    public void requestPasswordReset(ForgotPasswordRequest request) {
        String email = User.normalizeEmail(request.email());

        userRepository.findByEmail(email).ifPresent(user -> {
            if (user.getStatus() != UserStatus.DELETED) {
                user.issuePasswordResetCode(newResetCode(), now(), properties.passwordResetCodeTtl());
                userRepository.save(user);
                mailSender.sendPasswordResetEmail(email, user.getPasswordResetCode());
            }
        });
    }

    /**
     * 凭一次性重置码设置新密码。
     *
     * <p>成功后密码变更时刻被写入，此前签发的令牌一律失效（见
     * {@code UserStatusFilter} 与 {@link #refresh(String)}）。
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        User user = userRepository.findByPasswordResetCode(request.code())
                .orElseThrow(AuthException::invalidResetCode);

        if (user.getStatus() == UserStatus.DELETED) {
            throw AuthException.invalidResetCode();
        }
        if (!user.consumePasswordResetCode(request.code(), now())) {
            throw AuthException.invalidResetCode();
        }

        user.changePassword(passwordHasher.hash(request.newPassword()), now());
        userRepository.save(user);

        notifyPasswordChanged(user.getEmail());
    }

    /**
     * 锁定响应附带重试等待秒数，供前端展示倒计时而非静态文案。
     * 字段缺失时前端退化为静态提示，不影响主流程。
     */
    private AuthException lockedWithRetryAfter(User user) {
        long seconds = Duration.between(now(), user.getLockedUntil()).getSeconds();
        return new AuthException(ErrorCode.ACCOUNT_LOCKED,
                ErrorCode.ACCOUNT_LOCKED.getDefaultMessage(),
                Map.of("retryAfterSeconds", Math.max(seconds, 0)));
    }

    /**
     * 发送「密码已变更」通知。<b>失败不得回滚重置结果</b>——
     * 重置已完成，仅记录日志。否则邮件服务抖动会堵死密码重置。
     */
    private void notifyPasswordChanged(String email) {
        try {
            mailSender.sendPasswordChangedNotice(email);
        } catch (Exception ex) {
            log.error("Password-changed notice failed to send; reset already applied. to={}", email, ex);
        }
    }

    // ---------- helpers ----------

    private String newVerificationCode() {
        return UUID.randomUUID().toString();
    }

    private String newResetCode() {
        return UUID.randomUUID().toString();
    }

    private Instant now() {
        return clock.instant();
    }
}
