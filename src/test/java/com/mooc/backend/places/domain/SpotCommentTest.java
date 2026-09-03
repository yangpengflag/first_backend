package com.mooc.backend.places.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SpotCommentTest {

    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
    private static final String SLUG = "hangzhou-west-lake";
    private static final UUID USER = UUID.randomUUID();

    @Test
    void createAssignsIdAndTimestamps() {
        SpotComment comment = SpotComment.create(SLUG, USER, "Nice trail!", null, NOW);

        assertThat(comment.getId()).isNotNull();
        assertThat(comment.getSpotSlug()).isEqualTo(SLUG);
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
        SpotComment reply = SpotComment.create(SLUG, USER, "agreed", parent, NOW);

        assertThat(reply.getParentCommentId()).isEqualTo(parent);
        assertThat(reply.isTopLevel()).isFalse();
    }

    @Test
    void softDeleteMarksDeletedAndTouchesUpdatedAt() {
        SpotComment comment = SpotComment.create(SLUG, USER, "c", null, NOW);
        Instant later = NOW.plusSeconds(30);

        comment.softDelete(later);

        assertThat(comment.isDeleted()).isTrue();
        assertThat(comment.getUpdatedAt()).isEqualTo(later);
    }
}
