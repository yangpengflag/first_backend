package com.mooc.backend.posts.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PostTest {

    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
    private static final UUID AUTHOR = UUID.randomUUID();

    @Test
    void createAssignsIdAndDefaults() {
        Post post = Post.create(AUTHOR, "Title", "Body", null, List.of(), PostStatus.DRAFT, null, List.of(), NOW);

        assertThat(post.getId()).isNotNull();
        assertThat(post.getStatus()).isEqualTo(PostStatus.DRAFT);
        assertThat(post.getTags()).isEmpty();
        assertThat(post.getAuthorId()).isEqualTo(AUTHOR);
        assertThat(post.getCreatedAt()).isEqualTo(NOW);
        assertThat(post.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void updateReplacesProvidedFieldsAndTouchesUpdatedAt() {
        Post post = Post.create(AUTHOR, "Title", "Body", null, List.of(), PostStatus.DRAFT, null, List.of(), NOW);
        Instant later = NOW.plusSeconds(60);

        post.update("NewTitle", "NewBody", "http://x/y.png",
                List.of("a", "b"), PostStatus.PUBLISHED, null, List.of(), later);

        assertThat(post.getTitle()).isEqualTo("NewTitle");
        assertThat(post.getContent()).isEqualTo("NewBody");
        assertThat(post.getCoverImageUrl()).isEqualTo("http://x/y.png");
        assertThat(post.getTags()).containsExactly("a", "b");
        assertThat(post.getStatus()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(post.getUpdatedAt()).isEqualTo(later);
    }

    @Test
    void isPublishedReflectsStatus() {
        Post draft = Post.create(AUTHOR, "T", "c", null, List.of(), PostStatus.DRAFT, null, List.of(), NOW);
        Post published = Post.create(AUTHOR, "T", "c", null, List.of(), PostStatus.PUBLISHED, null, List.of(), NOW);

        assertThat(draft.isPublished()).isFalse();
        assertThat(published.isPublished()).isTrue();
    }
}
