package com.mooc.backend.auth.api;

/**
 * 登录成功响应体。
 *
 * <p>{@code user} 一律经 {@link UserResponse} 白名单转换，
 * 保证凭证类字段不会随令牌响应一同出网。
 */
public record AuthTokenResponse(
        String accessToken,
        String refreshToken,
        UserResponse user
) {
}
