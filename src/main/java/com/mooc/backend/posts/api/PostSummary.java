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
 *
 * <p>互动统计字段（comment_count / up_vote_count / bookmark_count）由列表聚合查询实时填充，
 * 不在 {@code Post} 实体冗余存储；详情等其它端点复用 {@link #from(Post, String, String, String)} 时计数为 0。
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
    @JsonProperty("comment_count") private final long commentCount;
    @JsonProperty("up_vote_count") private final long upVoteCount;
    @JsonProperty("bookmark_count") private final long bookmarkCount;
    @JsonProperty("city_id") private final String cityId;
    @JsonProperty("spot_ids") private final List<String> spotIds;

    public PostSummary(UUID id, String title, String coverImageUrl, List<String> tags, String status,
                       UUID authorId, String authorName, String authorAvatarUrl, String summary,
                       Instant createdAt, long commentCount, long upVoteCount, long bookmarkCount,
                       String cityId, List<String> spotIds) {
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
        this.commentCount = commentCount;
        this.upVoteCount = upVoteCount;
        this.bookmarkCount = bookmarkCount;
        this.cityId = cityId;
        this.spotIds = spotIds == null ? List.of() : spotIds;
    }

    public static final Set<String> WHITELISTED_FIELDS = Set.of(
            "id", "title", "cover_image_url", "tags", "status",
            "author_id", "author_name", "author_avatar_url", "summary", "created_at",
            "comment_count", "up_vote_count", "bookmark_count", "city_id", "spot_ids", "request_id");

    /** 列表专用：携带三项实时计数与地点关联。 */
    public static PostSummary from(Post post, String authorName, String authorAvatarUrl, String summary,
                                   long commentCount, long upVoteCount, long bookmarkCount) {
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
                post.getCreatedAt(),
                commentCount,
                upVoteCount,
                bookmarkCount,
                post.getCityId(),
                post.getSpotIds());
    }

    /** 详情 / 创建 / 编辑等端点复用：计数为 0（互动数不在此处提供），仍携带地点关联。 */
    public static PostSummary from(Post post, String authorName, String authorAvatarUrl, String summary) {
        return from(post, authorName, authorAvatarUrl, summary, 0L, 0L, 0L);
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
    public long getCommentCount() { return commentCount; }
    public long getUpVoteCount() { return upVoteCount; }
    public long getBookmarkCount() { return bookmarkCount; }
    public String getCityId() { return cityId; }
    public List<String> getSpotIds() { return spotIds; }
}
