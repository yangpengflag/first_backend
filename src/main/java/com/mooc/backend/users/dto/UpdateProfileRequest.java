package com.mooc.backend.users.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 档案编辑请求体（{@code PATCH /api/users/me}）。
 *
 * <p>PATCH 部分更新语义：字段为 {@code null}（JSON 中未传）表示不修改该字段；
 * 传空串有明确语义（{@code avatarUrl} / {@code bio} 空串表示清除）。
 * 长度上限由 jakarta validation 注解声明（{@code @Valid} 在 HTTP 层强制，
 * 违规由 {@code GlobalExceptionHandler} 统一输出 {@code 400 VALIDATION_FAILED}）；
 * 格式类校验（displayName 非纯空白、avatarUrl 协议白名单、tags 单条长度与去重）
 * 属业务规则，由 {@code ProfileService} 实施。
 */
public record UpdateProfileRequest(

        /** 可选：trim 后 1–30 字符且非纯空白。 */
        @Size(min = 1, max = 30, message = "must be between 1 and 30 characters")
        String displayName,

        /** 可选：≤2048 字符，协议仅允许 http/https（service 校验），空串表示清除。 */
        @Size(max = 2048, message = "must be at most 2048 characters")
        String avatarUrl,

        /** 可选：≤500 字符纯文本，空串表示清空。 */
        @Size(max = 500, message = "must be at most 500 characters")
        String bio,

        /** 可选：最多 8 个，单条 trim 后 1–30 字符（service 校验），空数组表示清空。 */
        @Size(max = 8, message = "must contain at most 8 tags")
        List<String> tags
) {
}
