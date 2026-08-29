package com.mooc.backend.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 提交新密码请求体。
 *
 * <p>密码上限 72 与注册一致：BCrypt 仅处理前 72 字节，超出部分被静默丢弃，
 * 放任超长密码会造成「密码很强」的错误认知。
 */
public record ResetPasswordRequest(

        @NotBlank(message = "must not be blank")
        String code,

        @NotBlank(message = "must not be blank")
        @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
        String newPassword
) {
}
