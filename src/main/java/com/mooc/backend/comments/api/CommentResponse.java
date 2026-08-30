package com.mooc.backend.comments.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.comments.domain.Comment;
import com.mooc.backend.dto.response.BaseResponse;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 评论出网白名单 DTO（snake_case，对齐 backend-conventions）。
 *
 * <p>结构性白名单：输出键严格等于 {@link #WHITELISTED_FIELDS}，绝不暴露 {@code deleted_at}
 * 等审计字段；作者信息仅限 {@code author_name} / {@author_avatar_url}，不携带 {@code email}。
 * 列表 / 回复端点亦复用本类（与 {@code PostSummary} 同构），由序列化测试断言回归。
 * 继承 {@code BaseResponse} 自带 request_id。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class CommentResponse extends BaseResponse {

    @JsonProperty("id") private final UUID id;
    @JsonProperty("post_id") private final UUID postId;
    @JsonProperty("user_id") private final UUID userId;
    @JsonProperty("parent_comment_id") private final UUID parentCommentId;
    @JsonProperty("content") private final String content;
    @JsonProperty("author_name") private final String authorName;
    @JsonProperty("author_avatar_url") private final String authorAvatarUrl;
    @JsonProperty("created_at") private final Instant createdAt;
    @JsonProperty("updated_at") private final Instant updatedAt;
    @JsonProperty("reply_count") private final long replyCount;

    public CommentResponse(UUID id, UUID postId, UUID userId, UUID parentCommentId, String content,
                           String authorName, String authorAvatarUrl, Instant createdAt,
                           Instant updatedAt, long replyCount) {
        super();
        this.id = id;
        this.postId = postId;
        this.userId = userId;
        this.parentCommentId = parentCommentId;
        this.content = content;
        this.authorName = authorName;
        this.authorAvatarUrl = authorAvatarUrl;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.replyCount = replyCount;
    }

    public static final Set<String> WHITELISTED_FIELDS = Set.of(
            "id", "post_id", "user_id", "parent_comment_id", "content", "author_name",
            "author_avatar_url", "created_at", "updated_at", "reply_count", "request_id");

    public static CommentResponse from(Comment comment, String authorName, String authorAvatarUrl, long replyCount) {
        return new CommentResponse(
                comment.getId(),
                comment.getPostId(),
                comment.getUserId(),
                comment.getParentCommentId(),
                comment.getContent(),
                authorName,
                authorAvatarUrl,
                comment.getCreatedAt(),
                comment.getUpdatedAt(),
                replyCount);
    }

    public UUID getId() { return id; }
    public UUID getPostId() { return postId; }
    public UUID getUserId() { return userId; }
    public UUID getParentCommentId() { return parentCommentId; }
    public String getContent() { return content; }
    public String getAuthorName() { return authorName; }
    public String getAuthorAvatarUrl() { return authorAvatarUrl; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getReplyCount() { return replyCount; }
}
