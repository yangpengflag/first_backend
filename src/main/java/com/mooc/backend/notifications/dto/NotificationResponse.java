package com.mooc.backend.notifications.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.dto.response.BaseResponse;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 单条通知出网 DTO（snake_case + request_id，Task 3.2，对齐 PostListResponse / ProfileResponse 模式）。
 *
 * <p>白名单结构：不含 actor 的 email 或任何凭证字段。{@code actor} 仅 id / display_name /
 * avatar_url——actor 已注销（软删）或缺失时 displayName / avatarUrl 为 {@code null}，
 * 条目本身照常返回，由前端以占位头像与 "A traveler" 降级展示（spec：actor 降级）。
 *
 * <p>{@code post} 为帖子引用（id / title）；帖子行物理缺失时 title 为 {@code null}，
 * 帖子软删时标题照常返回（条目保留、不级联删除，跳转由详情端点报 404）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class NotificationResponse extends BaseResponse {

    @JsonProperty("id")
    private final UUID id;

    @JsonProperty("type")
    private final String type;

    @JsonProperty("actor")
    private final Actor actor;

    @JsonProperty("post")
    private final PostRef post;

    @JsonProperty("comment_id")
    private final UUID commentId;

    @JsonProperty("read_at")
    private final Instant readAt;

    @JsonProperty("created_at")
    private final Instant createdAt;

    /** actor 身份视图；降级态 displayName / avatarUrl 为 null。 */
    public record Actor(UUID id,
                        @JsonProperty("display_name") String displayName,
                        @JsonProperty("avatar_url") String avatarUrl) {
    }

    /** 帖子引用视图。 */
    public record PostRef(UUID id, String title) {
    }

    public NotificationResponse(UUID id, String type, Actor actor, PostRef post,
                                UUID commentId, Instant readAt, Instant createdAt) {
        super();
        this.id = id;
        this.type = type;
        this.actor = actor;
        this.post = post;
        this.commentId = commentId;
        this.readAt = readAt;
        this.createdAt = createdAt;
    }

    /** 白名单字段集合，供集成测试断言序列化输出严格等于此集合（含 request_id）。 */
    public static final Set<String> WHITELISTED_FIELDS = Set.of(
            "id", "type", "actor", "post", "comment_id", "read_at", "created_at", "request_id");

    public UUID getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public Actor getActor() {
        return actor;
    }

    public PostRef getPost() {
        return post;
    }

    public UUID getCommentId() {
        return commentId;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
