package com.mooc.backend.entity;
import com.mooc.backend.entity.Bookmark;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BookmarkTest {

    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
    private static final UUID POST = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Test
    void createAssignsIdAndTimestamps() {
        Bookmark bookmark = Bookmark.create(POST, USER, NOW);

        assertThat(bookmark.getId()).isNotNull();
        assertThat(bookmark.getPostId()).isEqualTo(POST);
        assertThat(bookmark.getUserId()).isEqualTo(USER);
        assertThat(bookmark.getCreatedAt()).isEqualTo(NOW);
        assertThat(bookmark.isDeleted()).isFalse();
    }
}
