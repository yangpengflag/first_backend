package com.mooc.backend.auth.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.auth.domain.User;
import com.mooc.backend.dto.response.BaseResponse;

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
 * 响应形态对齐 {@code backend-conventions.md}：继承 {@code BaseResponse}（自带 request_id）、
 * 字段 snake_case、immutable。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class UserResponse extends BaseResponse {

    @JsonProperty("id") private final UUID id;
    @JsonProperty("email") private final String email;
    @JsonProperty("display_name") private final String displayName;
    @JsonProperty("avatar_url") private final String avatarUrl;
    @JsonProperty("status") private final String status;
    @JsonProperty("created_at") private final Instant createdAt;

    public UserResponse(UUID id, String email, String displayName, String avatarUrl,
                        String status, Instant createdAt) {
        super();
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.status = status;
        this.createdAt = createdAt;
    }

    /** 白名单字段集合，供测试断言序列化输出严格等于此集合（含 request_id）。 */
    public static final Set<String> WHITELISTED_FIELDS = Set.of(
            "id", "email", "display_name", "avatar_url", "status", "created_at", "request_id");

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
                user.getCreatedAt());
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getAvatarUrl() { return avatarUrl; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
