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
 * 帖子详情出网白名单 DTO（snake_case，对齐 backend-conventions）。
 *
 * <p>结构性白名单：输出键严格等于 {@link #WHITELISTED_FIELDS}，绝不暴露 {@code deleted_at}
 * 等审计字段；作者信息仅限 {@code author_name} / {@code author_avatar_url}，
 * 不得携带 {@code email} 等隐私字段。由 {@code PostResponseSerializationTest} 断言回归。
 * 继承 {@code BaseResponse} 自带 request_id。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class PostResponse extends BaseResponse {

    @JsonProperty("id") private final UUID id;
    @JsonProperty("title") private final String title;
    @JsonProperty("content") private final String content;
    @JsonProperty("cover_image_url") private final String coverImageUrl;
    @JsonProperty("tags") private final List<String> tags;
    @JsonProperty("status") private final String status;
    @JsonProperty("author_id") private final UUID authorId;
    @JsonProperty("author_name") private final String authorName;
    @JsonProperty("author_avatar_url") private final String authorAvatarUrl;
    @JsonProperty("summary") private final String summary;
    @JsonProperty("created_at") private final Instant createdAt;
    @JsonProperty("updated_at") private final Instant updatedAt;
    @JsonProperty("city_id") private final String cityId;
    @JsonProperty("spot_ids") private final List<String> spotIds;

    public PostResponse(UUID id, String title, String content, String coverImageUrl, List<String> tags,
                        String status, UUID authorId, String authorName, String authorAvatarUrl,
                        String summary, Instant createdAt, Instant updatedAt,
                        String cityId, List<String> spotIds) {
        super();
        this.id = id;
        this.title = title;
        this.content = content;
        this.coverImageUrl = coverImageUrl;
        this.tags = tags;
        this.status = status;
        this.authorId = authorId;
        this.authorName = authorName;
        this.authorAvatarUrl = authorAvatarUrl;
        this.summary = summary;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.cityId = cityId;
        this.spotIds = spotIds == null ? List.of() : spotIds;
    }

    public static final Set<String> WHITELISTED_FIELDS = Set.of(
            "id", "title", "content", "cover_image_url", "tags", "status",
            "author_id", "author_name", "author_avatar_url", "summary", "created_at", "updated_at",
            "city_id", "spot_ids", "request_id");

    public static PostResponse from(Post post, String authorName, String authorAvatarUrl, String summary) {
        return new PostResponse(
                post.getId(),
                post.getTitle(),
                post.getContent(),
                post.getCoverImageUrl(),
                post.getTags(),
                post.getStatus() == null ? null : post.getStatus().name(),
                post.getAuthorId(),
                authorName,
                authorAvatarUrl,
                summary,
                post.getCreatedAt(),
                post.getUpdatedAt(),
                post.getCityId(),
                post.getSpotIds());
    }

    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getCoverImageUrl() { return coverImageUrl; }
    public List<String> getTags() { return tags; }
    public String getStatus() { return status; }
    public UUID getAuthorId() { return authorId; }
    public String getAuthorName() { return authorName; }
    public String getAuthorAvatarUrl() { return authorAvatarUrl; }
    public String getSummary() { return summary; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getCityId() { return cityId; }
    public List<String> getSpotIds() { return spotIds; }
}
