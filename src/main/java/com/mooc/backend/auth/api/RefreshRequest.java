package com.mooc.backend.auth.api;

import jakarta.validation.constraints.NotBlank;

/** 刷新令牌请求体。 */
public record RefreshRequest(

        @NotBlank(message = "must not be blank")
        String refreshToken
) {
}
