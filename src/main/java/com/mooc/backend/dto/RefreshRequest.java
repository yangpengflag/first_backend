package com.mooc.backend.dto;

import jakarta.validation.constraints.NotBlank;

/** 刷新令牌请求体。 */
public record RefreshRequest(

        @NotBlank(message = "must not be blank")
        String refreshToken
) {
}
