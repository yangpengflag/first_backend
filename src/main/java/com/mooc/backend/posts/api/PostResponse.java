package com.mooc.backend.posts.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mooc.backend.posts.domain.Post;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 帖子详情出网白名单 DTO（camelCase，与 {@code UserResponse} 保持一致）。
 *
 * <p>结构性白名单：输出键严格等于 {@link #WHITELISTED_FIELDS}，绝不暴露 {@code deletedAt}
 * 等审计字段；作者信息仅限 {@code authorName} / {@code authorAvatarUrl}，
 * 不得携带 {@code email} 等隐私字段。由 {@code PostResponseSerializationTest} 断言回归。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PostResponse(
        UUID id,
        String title,
        String content,
        String coverImageUrl,
        List<String> tags,
        String status,
        UUID authorId,
        String authorName,
        String authorAvatarUrl,
        String summary,
        Instant createdAt,
        Instant updatedAt
) {

    public static final Set<String> WHITELISTED_FIELDS = Set.of(
            "id", "title", "content", "coverImageUrl", "tags", "status",
            "authorId", "authorName", "authorAvatarUrl", "summary", "createdAt", "updatedAt");

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
                post.getUpdatedAt());
    }
}
