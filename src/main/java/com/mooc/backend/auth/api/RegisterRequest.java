package com.mooc.backend.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注册请求体。
 *
 * <p>校验失败由 {@code GlobalExceptionHandler} 统一输出
 * {@code 400 VALIDATION_FAILED} + 逐字段 details。
 */
public record RegisterRequest(

        @NotBlank(message = "must not be blank")
        @Email(message = "must be a valid email address")
        String email,

        /**
         * 上限 72 源于 BCrypt 仅处理前 72 字节，超出部分被静默丢弃。
         * 不设上限会让用户误以为超长密码更强，实际只有前 72 字节生效。
         */
        @NotBlank(message = "must not be blank")
        @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
        String password,

        @NotBlank(message = "must not be blank")
        @Size(max = 64, message = "must be at most 64 characters")
        String displayName
) {
}
