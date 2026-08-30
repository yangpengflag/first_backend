package com.mooc.backend.comments.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CommentTest {

    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
    private static final UUID POST = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Test
    void createAssignsIdAndTimestamps() {
        Comment comment = Comment.create(POST, USER, "Nice trail!", null, NOW);

        assertThat(comment.getId()).isNotNull();
        assertThat(comment.getPostId()).isEqualTo(POST);
        assertThat(comment.getUserId()).isEqualTo(USER);
        assertThat(comment.getContent()).isEqualTo("Nice trail!");
        assertThat(comment.getParentCommentId()).isNull();
        assertThat(comment.isTopLevel()).isTrue();
        assertThat(comment.getCreatedAt()).isEqualTo(NOW);
        assertThat(comment.getUpdatedAt()).isEqualTo(NOW);
        assertThat(comment.isDeleted()).isFalse();
    }

    @Test
    void replyIsNotTopLevel() {
        UUID parent = UUID.randomUUID();
        Comment reply = Comment.create(POST, USER, "agreed", parent, NOW);

        assertThat(reply.getParentCommentId()).isEqualTo(parent);
        assertThat(reply.isTopLevel()).isFalse();
    }

    @Test
    void softDeleteMarksDeletedAndTouchesUpdatedAt() {
        Comment comment = Comment.create(POST, USER, "c", null, NOW);
        Instant later = NOW.plusSeconds(30);

        comment.softDelete(later);

        assertThat(comment.isDeleted()).isTrue();
        assertThat(comment.getUpdatedAt()).isEqualTo(later);
    }
}
