package com.mooc.backend.auth.api;

import com.mooc.backend.auth.exception.AuthException;
import com.mooc.backend.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 认证 HTTP 接口。
 *
 * <p>免鉴权端点（register / login / verify / resend-verification / refresh）
 * 由 {@code SecurityConfig.PUBLIC_ENDPOINTS} 放行；
 * me / logout / delete-me 需持有有效令牌，且经 {@code UserStatusFilter} 校验当前状态。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 注册 → 201；不签发令牌，需先完成邮箱验证。 */
    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    /** 登录 → 200；状态非 ACTIVE 时按状态机返回 401 / 403 / 423。 */
    @PostMapping("/login")
    public ResponseEntity<AuthTokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /**
     * 邮箱验证（<b>免鉴权</b>）——破解 email_unverified 死锁的关键端点。
     * 用户无需登录，凭邮件链接中的一次性验证码即可激活账号。
     */
    @GetMapping("/verify")
    public ResponseEntity<UserResponse> verify(@RequestParam("code") String code) {
        return ResponseEntity.ok(authService.verifyEmail(code));
    }

    /** 重发验证邮件 → 恒定 202，不泄露邮箱是否存在。 */
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerification(request);
        return ResponseEntity.accepted().build();
    }

    /**
     * 申请密码重置 → 恒定 `202`。
     * 无论邮箱是否存在、是否已注销都返回同一结果，不泄露账号存在性。
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.requestPasswordReset(request);
        return ResponseEntity.accepted().build();
    }

    /** 凭邮件中的一次性重置码设置新密码 → `200`。 */
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok().build();
    }

    /** 刷新 access token。 */
    @PostMapping("/refresh")
    public ResponseEntity<AuthTokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request.refreshToken()));
    }

    /** 登出 → 204。JWT 无状态，由客户端丢弃令牌。 */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }

    /** 当前用户信息。 */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me() {
        return ResponseEntity.ok(authService.getCurrentUser(currentUserId()));
    }

    /** 注销账号（软删除）→ 204。 */
    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteMe() {
        authService.deleteAccount(currentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * 从 SecurityContext 取当前用户标识。
     * {@code JwtAuthFilter} 以用户 UUID 字符串作为 principal name。
     */
    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw AuthException.unauthenticated();
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ex) {
            throw AuthException.unauthenticated();
        }
    }
}
