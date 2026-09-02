package com.mooc.backend.auth.exception;

import org.springframework.http.HttpStatus;

/**
 * 认证域错误码枚举，每个枚举值同时绑定 HTTP 状态码与默认文案。
 *
 * <p>响应码映射依据 openspec/specs/auth-module/spec.md「用户状态机与登录响应码映射」：
 * ACTIVE → 200、LOCKED → 423、DELETED → 401、EMAIL_UNVERIFIED → 403。
 */
public enum ErrorCode {

    /** 邮箱不存在 或 密码错误。二者共用同一错误码以收窄账号枚举面。 */
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid email or password."),

    /** 账号已注销（软删除）。 */
    ACCOUNT_DELETED(HttpStatus.UNAUTHORIZED, "This account has been deleted."),

    /** 未提供凭证、凭证无法解析或已过期。 */
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required."),

    /** 邮箱未验证：认证成立但授权不足。 */
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "Please verify your email address before signing in."),

    /** 账号被锁定（连续失败达阈值 或 管理员锁定）。 */
    ACCOUNT_LOCKED(HttpStatus.LOCKED, "Account is temporarily locked. Try again later."),

    /** 请求体参数校验失败。 */
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed."),

    /** 邮箱验证 code 无效 / 已过期 / 已使用（三态同码，不泄露细节）。 */
    INVALID_VERIFICATION_CODE(HttpStatus.BAD_REQUEST, "Invalid or expired verification code."),

    /** 密码重置码无效 / 已过期 / 已使用（三态同码，不泄露细节）。 */
    INVALID_RESET_CODE(HttpStatus.BAD_REQUEST, "Invalid or expired password reset code."),

    /**
     * 令牌签发于密码变更之前，已作废。
     * 用于阻断攻击者持旧令牌持续访问（无状态 JWT 下的「改密即登出」）。
     */
    TOKEN_INVALIDATED(HttpStatus.UNAUTHORIZED, "Token is no longer valid. Please sign in again."),

    /** 邮箱已被注册（含大小写变体）。 */
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "This email is already registered."),

    /** 触发限流。 */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please try again later."),

    /** 帖子不存在、已被软删，或非公开（DRAFT）状态。 */
    POST_NOT_FOUND(HttpStatus.NOT_FOUND, "Post not found."),

    /** 城市不存在（slug 未命中）。 */
    CITY_NOT_FOUND(HttpStatus.NOT_FOUND, "City not found."),

    /** 景点不存在（slug 未命中）。 */
    SPOT_NOT_FOUND(HttpStatus.NOT_FOUND, "Spot not found."),

    /** 当前用户不是该帖子的作者，无权编辑。 */
    NOT_POST_AUTHOR(HttpStatus.FORBIDDEN, "You are not the author of this post."),

    /** 评论不存在或已软删。 */
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Comment not found."),

    /** 当前用户不是该评论的作者，无权删除。 */
    NOT_COMMENT_AUTHOR(HttpStatus.FORBIDDEN, "You are not the author of this comment."),

    /** 回复的父评论不存在、跨帖，或父评论本身已是回复（不允许嵌套）。 */
    INVALID_PARENT_COMMENT(HttpStatus.BAD_REQUEST, "Invalid parent comment."),

    /** 未预期的服务端错误。 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
