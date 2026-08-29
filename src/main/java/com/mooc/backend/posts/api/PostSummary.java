package com.mooc.backend.posts.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.mooc.backend.posts.domain.Post;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 帖子列表项出网白名单 DTO（不含 {@code content} / {@code updatedAt}，列表无需）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PostSummary(
        UUID id,
        String title,
        String coverImageUrl,
        List<String> tags,
        String status,
        UUID authorId,
        String authorName,
        String authorAvatarUrl,
        String summary,
        Instant createdAt
) {

    public static final Set<String> WHITELISTED_FIELDS = Set.of(
            "id", "title", "coverImageUrl", "tags", "status",
            "authorId", "authorName", "authorAvatarUrl", "summary", "createdAt");

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
}
