package com.mooc.backend.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求体。
 *
 * <p>密码不做长度校验——错误密码属正常业务分支（401），
 * 不应当作参数校验错误处理。
 */
public record LoginRequest(

        @NotBlank(message = "must not be blank")
        @Email(message = "must be a valid email address")
        String email,

        @NotBlank(message = "must not be blank")
        String password
) {
}
