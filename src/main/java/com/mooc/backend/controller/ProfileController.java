package com.mooc.backend.controller;

import com.mooc.backend.exception.AuthException;
import com.mooc.backend.dto.response.ProfileResponse;
import com.mooc.backend.dto.UpdateProfileRequest;
import com.mooc.backend.service.ProfileService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * 本人档案 HTTP 接口（change: add-user-profile）。
 *
 * <p>两端点均需持有有效令牌（{@code anyRequest().authenticated()} 兜底 401），
 * 且经 {@code UserStatusFilter} 校验当前状态——LOCKED/EMAIL_UNVERIFIED/DELETED
 * 分别 423/403/401，零新增门禁逻辑。档案读取与编辑独立于认证端点：
 * {@code GET /api/auth/me} 保持会话身份用途与契约不变。
 *
 * <p>本类的 OpenAPI 注解仅用于文档产出，<b>不改变任何运行期行为</b>；
 * 错误路径由 {@code GlobalExceptionHandler} 产出，springdoc 不会自动感知，
 * 故 400/401/403/423 必须在此手工标注。
 */
@Tag(name = "用户档案", description = """
        本人档案的查看与编辑（昵称 / 头像 / 个人简介 / 兴趣标签）。
        所有错误响应使用统一信封 {"error":{"code":...,"message":...,"details":...}}，
        前端一律基于 error.code 分支而非 HTTP 状态码。""")
// produces 必须显式声明为 application/json：对齐 AuthController，保证契约 media type 精确。
@RestController
@RequestMapping(value = "/api/users", produces = MediaType.APPLICATION_JSON_VALUE)
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    /** 当前用户完整档案 → 200。 */
    @Operation(summary = "查看本人档案", description = """
            返回当前登录用户完整档案（含 bio / tags，未设置时为 null）。
            响应字段为白名单（id / email / display_name / avatar_url / bio / tags /
            status / role / created_at + request_id），不含任何凭证类字段。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回本人档案"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED / ACCOUNT_DELETED / TOKEN_INVALIDATED"),
            @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED：邮箱未验证"),
            @ApiResponse(responseCode = "423", description = "ACCOUNT_LOCKED：账号已锁定")
    })
    @GetMapping("/me")
    public ResponseEntity<ProfileResponse> me() {
        return ResponseEntity.ok(profileService.getProfile(currentUserId()));
    }

    /** 部分更新本人档案 → 200（返回更新后的完整档案）。 */
    @Operation(summary = "编辑本人档案", description = """
            部分更新：请求体中未传（null）的字段不修改，空对象 {} 幂等。
            displayName trim 后 1-30 字符且非纯空白；avatarUrl ≤2048 字符且协议仅允许
            http/https（javascript:、data: 等伪协议被拒绝），空字符串表示清除；
            bio ≤500 字符纯文本，空字符串表示清空；tags 最多 8 个、单条 trim 后
            1-30 字符、大小写不敏感去重（保留首次输入形态），空数组表示清空。""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "返回更新后的完整档案"),
            @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED：字段超长 / displayName 纯空白 / avatarUrl 协议非法 / tags 单条超长等"),
            @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED / ACCOUNT_DELETED / TOKEN_INVALIDATED"),
            @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED：邮箱未验证"),
            @ApiResponse(responseCode = "423", description = "ACCOUNT_LOCKED：账号已锁定")
    })
    @PatchMapping("/me")
    public ResponseEntity<ProfileResponse> updateMe(@Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(profileService.updateProfile(currentUserId(), request, Instant.now()));
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
