package com.mooc.backend.auth.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mooc.backend.auth.domain.User;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 用户出网白名单 DTO——<b>本模块唯一允许用户数据离开服务端的表示</b>。
 *
 * <p>采用白名单而非序列化黑名单：新增实体字段默认不可见，必须在此显式声明才会输出。
 * 结构性杜绝 {@code passwordHash} / {@code salt} / {@code verificationCode} 泄露。
 *
 * <p>由 {@code UserResponseSerializationTest} 断言键集合严格相等，作为回归护栏。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record UserResponse(
        UUID id,
        String email,
        String displayName,
        String avatarUrl,
        String status,
        Instant createdAt
) {

    /** 白名单字段集合，供测试断言序列化输出严格等于此集合。 */
    public static final Set<String> WHITELISTED_FIELDS =
            Set.of("id", "email", "displayName", "avatarUrl", "status", "createdAt");

    public static UserResponse from(User user) {
        if (user == null) {
            return null;
        }
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                user.getStatus() == null ? null : user.getStatus().name(),
                user.getCreatedAt()
        );
    }
}
