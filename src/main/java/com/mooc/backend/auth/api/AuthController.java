package com.mooc.backend.auth.api;

import com.mooc.backend.auth.exception.AuthException;
import com.mooc.backend.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 *
 * <p>本类的 OpenAPI 注解（change: openapi-integration）仅用于文档产出，
 * <b>不改变任何运行期行为</b>——响应语义仍由 Service 与过滤器链决定。
 * 错误路径由 {@code GlobalExceptionHandler} 产出，springdoc 不会自动感知，
 * 故四态（200/401/403/423）与 429 必须在此手工标注。
 */
@Tag(name = "认证", description = """
        注册 / 登录 / 邮箱验证 / 密码重置 / 会话维持。
        所有错误响应使用统一信封 {"error":{"code":...,"message":...,"details":...}}，
        前端一律基于 error.code 分支而非 HTTP 状态码。""")
// produces 必须显式声明为 application/json：否则 springdoc 会把所有响应
// （含 200 成功响应）的 content type 推断为通配符，契约因此不精确，
// 任何按 media type 协商的消费方都无法正确解析。
@RestController
@RequestMapping(value = "/api/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 注册 → 201；不签发令牌，需先完成邮箱验证。
     */
    @Operation(summary = "注册", description = "以 EMAIL_UNVERIFIED 状态创建用户，**不签发任何令牌**，需先完成邮箱验证方可登录。")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "注册成功，响应体 user.status 为 EMAIL_UNVERIFIED 且不含任何令牌"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED：邮箱格式非法或密码不在 8–72 字符区间"),
            @ApiResponse(responseCode = "409", description = "EMAIL_ALREADY_REGISTERED：邮箱已注册（含已注销账号，邮箱不可复用）"),
            @ApiResponse(responseCode = "429", description = "RATE_LIMITED：同一 IP 注册过于频繁")
    })
    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    /**
     * 登录 → 200；状态非 ACTIVE 时按状态机返回 401 / 403 / 423。
     */
    @Operation(summary = "登录", description = """
            按用户**当前**状态返回精确响应码：
            ACTIVE→200、LOCKED→423、DELETED→401、EMAIL_UNVERIFIED→403。
            邮箱不存在与密码错误共用 401 INVALID_CREDENTIALS，以收窄账号枚举面。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "登录成功，签发 access / refresh 令牌"),
            @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS（邮箱不存在或密码错误）/ ACCOUNT_DELETED（账号已注销）"),
            @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED：邮箱未验证，前端须提供重发验证邮件的出路"),
            @ApiResponse(responseCode = "423", description = "ACCOUNT_LOCKED：账号已锁定，响应含 retryAfterSeconds"),
            @ApiResponse(responseCode = "429", description = "RATE_LIMITED：登录尝试过于频繁（IP 与 IP+email 双维度计数）")
    })
    @PostMapping("/login")
    public ResponseEntity<AuthTokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /**
     * 邮箱验证（<b>免鉴权</b>）——破解 email_unverified 死锁的关键端点。
     * 用户无需登录，凭邮件链接中的一次性验证码即可激活账号。
     */
    @Operation(summary = "邮箱验证（免鉴权）", description = """
            凭邮件链接中的一次性验证码激活账号，**无需登录**。
            这是破解「登录返回 403 但验证邮件需登录态」死锁的关键端点。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "验证成功，用户状态转为 ACTIVE，验证码用后即焚"),
            @ApiResponse(responseCode = "400", description = "INVALID_VERIFICATION_CODE：验证码无效 / 已过期 / 已使用（三态同码，不泄露具体原因）")
    })
    @GetMapping("/verify")
    public ResponseEntity<UserResponse> verify(@RequestParam("code") String code) {
        return ResponseEntity.ok(authService.verifyEmail(code));
    }

    /** 重发验证邮件 → 恒定 202，不泄露邮箱是否存在。 */
    @Operation(summary = "重发验证邮件（免鉴权）", description = """
            恒定返回 202：无论邮箱是否存在或是否已验证，均返回同一结果，不泄露账号存在性。
            仅当邮箱存在且状态为 EMAIL_UNVERIFIED 时才实际投递邮件。""")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "已受理（恒定成功，不透露邮箱是否已注册）"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED：邮箱格式非法"),
            @ApiResponse(responseCode = "429", description = "RATE_LIMITED：重发过于频繁")
    })
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerification(request);
        return ResponseEntity.accepted().build();
    }

    /**
     * 申请密码重置 → 恒定 `202`。
     * 无论邮箱是否存在、是否已注销都返回同一结果，不泄露账号存在性。
     */
    @Operation(summary = "申请密码重置（免鉴权）", description = """
            恒定返回 202：无论邮箱是否存在、是否已注销都返回同一结果，不泄露账号存在性。
            仅当邮箱存在且状态非 DELETED 时才实际投递重置邮件。""")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "已受理（恒定成功，响应体为固定文案）"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED：邮箱格式非法"),
            @ApiResponse(responseCode = "429", description = "RATE_LIMITED：申请过于频繁")
    })
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.requestPasswordReset(request);
        return ResponseEntity.accepted().build();
    }

    /** 凭邮件中的一次性重置码设置新密码 → `200`。 */
    @Operation(summary = "重置密码（免鉴权）", description = """
            凭邮件中的一次性重置码设置新密码。
            重置成功会把未验证邮箱一并置为 ACTIVE，并使密码变更前签发的令牌全部失效。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "重置成功；重置码用后即焚，failedAttempts 归零"),
            @ApiResponse(responseCode = "400", description = "INVALID_RESET_CODE（无效 / 已过期 / 已使用，三态同码）或 VALIDATION_FAILED（密码不在 8–72 字符）"),
            @ApiResponse(responseCode = "429", description = "RATE_LIMITED：重置尝试过于频繁")
    })
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok().build();
    }

    /** 刷新 access token。 */
    @Operation(summary = "刷新 access token", description = "以 refresh token 换取新签发的 access / refresh 令牌。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "已签发新的 access / refresh 令牌"),
            @ApiResponse(responseCode = "401", description = "TOKEN_INVALIDATED（令牌早于密码变更）/ ACCOUNT_DELETED（账号已注销）"),
            @ApiResponse(responseCode = "423", description = "ACCOUNT_LOCKED：账号已锁定")
    })
    @PostMapping("/refresh")
    public ResponseEntity<AuthTokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request.refreshToken()));
    }

    /** 登出 → 204。JWT 无状态，由客户端丢弃令牌。 */
    @Operation(summary = "登出", description = "JWT 无状态，服务端不维护令牌黑名单；客户端须丢弃本地 access / refresh 令牌。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "登出成功"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED：未携带有效令牌")
    })
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }

    /** 当前用户信息。 */
    @Operation(summary = "当前用户信息", description = """
            返回当前登录用户信息，同时用于校验本地令牌是否仍有效。
            反映用户**当前**状态而非仅令牌签名有效性——登录后被锁定或注销会即时生效。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回当前用户信息（不含任何凭证类字段）"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED / ACCOUNT_DELETED / TOKEN_INVALIDATED"),
            @ApiResponse(responseCode = "423", description = "ACCOUNT_LOCKED：账号已锁定")
    })
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me() {
        return ResponseEntity.ok(authService.getCurrentUser(currentUserId()));
    }

    /** 注销账号（软删除）→ 204。 */
    @Operation(summary = "注销账号（软删除）", description = """
            执行软删除：用户行保留，状态置为 DELETED 并写入 deletedAt。
            邮箱不可重新注册；已有业务数据（攻略、评论等）不物理删除。""")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "注销成功"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED：未携带有效令牌"),
            @ApiResponse(responseCode = "423", description = "ACCOUNT_LOCKED：账号已锁定")
    })
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
