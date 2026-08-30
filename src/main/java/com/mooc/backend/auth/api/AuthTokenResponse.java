package com.mooc.backend.auth.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.dto.response.BaseResponse;

/**
 * 登录成功响应体。
 *
 * <p>{@code user} 一律经 {@link UserResponse} 白名单转换，
 * 保证凭证类字段不会随令牌响应一同出网。继承 {@code BaseResponse} 自带 request_id。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class AuthTokenResponse extends BaseResponse {

    @JsonProperty("access_token") private final String accessToken;
    @JsonProperty("refresh_token") private final String refreshToken;
    @JsonProperty("user") private final UserResponse user;

    public AuthTokenResponse(String accessToken, String refreshToken, UserResponse user) {
        super();
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.user = user;
    }

    public String getAccessToken() { return accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public UserResponse getUser() { return user; }
}
