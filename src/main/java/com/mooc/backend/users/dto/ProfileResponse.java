package com.mooc.backend.users.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.auth.domain.User;
import com.mooc.backend.dto.response.BaseResponse;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 本人档案出网白名单 DTO——<b>users 模块唯一允许用户数据离开服务端的表示</b>。
 *
 * <p>独立于 {@code UserResponse}（auth 各端点契约零变化）：档案端点在既有字段之上
 * 额外暴露 {@code bio} / {@code tags}。采用白名单而非黑名单：新增实体字段默认不可见，
 * 必须在此显式声明才会输出，结构性杜绝 {@code passwordHash} / {@code salt} /
 * {@code verificationCode} / {@code passwordResetCode} 泄露。
 *
 * <p>由 {@code ProfileResponseSerializationTest} 断言键集合严格相等，作为回归护栏。
 * {@code bio} / {@code tags} 未设置时输出 {@code null}（非缺失），
 * 前端统一按「未设置」空态展示。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ProfileResponse extends BaseResponse {

    @JsonProperty("id") private final UUID id;
    @JsonProperty("email") private final String email;
    @JsonProperty("display_name") private final String displayName;
    @JsonProperty("avatar_url") private final String avatarUrl;
    @JsonProperty("bio") private final String bio;
    @JsonProperty("tags") private final List<String> tags;
    @JsonProperty("status") private final String status;
    @JsonProperty("role") private final String role;
    @JsonProperty("created_at") private final Instant createdAt;

    public ProfileResponse(UUID id, String email, String displayName, String avatarUrl,
                           String bio, List<String> tags, String status, String role, Instant createdAt) {
        super();
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.bio = bio;
        this.tags = tags == null ? null : List.copyOf(tags);
        this.status = status;
        this.role = role;
        this.createdAt = createdAt;
    }

    /** 白名单字段集合，供测试断言序列化输出严格等于此集合（含 request_id）。 */
    public static final Set<String> WHITELISTED_FIELDS = Set.of(
            "id", "email", "display_name", "avatar_url", "bio", "tags",
            "status", "role", "created_at", "request_id");

    public static ProfileResponse from(User user) {
        if (user == null) {
            return null;
        }
        return new ProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                user.getBio(),
                user.getTags(),
                user.getStatus() == null ? null : user.getStatus().name(),
                user.getRole() == null ? null : user.getRole().name(),
                user.getCreatedAt());
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getAvatarUrl() { return avatarUrl; }
    public String getBio() { return bio; }
    public List<String> getTags() { return tags == null ? null : List.copyOf(tags); }
    public String getStatus() { return status; }
    public String getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
}
