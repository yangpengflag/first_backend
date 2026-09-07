package com.mooc.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** 重发验证邮件请求体。 */
public record ResendVerificationRequest(

        @NotBlank(message = "must not be blank")
        @Email(message = "must be a valid email address")
        String email
) {
}
