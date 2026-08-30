package com.mooc.backend.posts.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.dto.response.BaseResponse;
import com.mooc.backend.posts.domain.Post;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 帖子列表项出网白名单 DTO（不含 {@code content} / {@code updated_at}，列表无需）。
 * snake_case 字段，继承 {@code BaseResponse} 自带 request_id。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class PostSummary extends BaseResponse {

    @JsonProperty("id") private final UUID id;
    @JsonProperty("title") private final String title;
    @JsonProperty("cover_image_url") private final String coverImageUrl;
    @JsonProperty("tags") private final List<String> tags;
    @JsonProperty("status") private final String status;
    @JsonProperty("author_id") private final UUID authorId;
    @JsonProperty("author_name") private final String authorName;
    @JsonProperty("author_avatar_url") private final String authorAvatarUrl;
    @JsonProperty("summary") private final String summary;
    @JsonProperty("created_at") private final Instant createdAt;

    public PostSummary(UUID id, String title, String coverImageUrl, List<String> tags, String status,
                       UUID authorId, String authorName, String authorAvatarUrl, String summary,
                       Instant createdAt) {
        super();
        this.id = id;
        this.title = title;
        this.coverImageUrl = coverImageUrl;
        this.tags = tags;
        this.status = status;
        this.authorId = authorId;
        this.authorName = authorName;
        this.authorAvatarUrl = authorAvatarUrl;
        this.summary = summary;
        this.createdAt = createdAt;
    }

    public static final Set<String> WHITELISTED_FIELDS = Set.of(
            "id", "title", "cover_image_url", "tags", "status",
            "author_id", "author_name", "author_avatar_url", "summary", "created_at", "request_id");

    public static PostSummary from(Post post, String authorName, String authorAvatarUrl, String summary) {
        return new PostSummary(
                post.getId(),
                post.getTitle(),
                post.getCoverImageUrl(),
                post.getTags(),
                post.getStatus() == null ? null : post.getStatus().name(),
                post.getAuthorId(),
                authorName,
                authorAvatarUrl,
                summary,
                post.getCreatedAt());
    }

    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public String getCoverImageUrl() { return coverImageUrl; }
    public List<String> getTags() { return tags; }
    public String getStatus() { return status; }
    public UUID getAuthorId() { return authorId; }
    public String getAuthorName() { return authorName; }
    public String getAuthorAvatarUrl() { return authorAvatarUrl; }
    public String getSummary() { return summary; }
    public Instant getCreatedAt() { return createdAt; }
}
