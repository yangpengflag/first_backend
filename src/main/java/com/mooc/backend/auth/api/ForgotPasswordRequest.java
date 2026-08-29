package com.mooc.backend.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** 申请密码重置请求体。 */
public record ForgotPasswordRequest(

        @NotBlank(message = "must not be blank")
        @Email(message = "must be a valid email address")
        String email
) {
}
