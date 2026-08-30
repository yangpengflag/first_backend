package com.mooc.backend.votes.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VoteTest {

    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
    private static final UUID POST = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Test
    void createAssignsIdAndTimestamps() {
        Vote vote = Vote.create(POST, USER, VoteType.UP, NOW);

        assertThat(vote.getId()).isNotNull();
        assertThat(vote.getPostId()).isEqualTo(POST);
        assertThat(vote.getUserId()).isEqualTo(USER);
        assertThat(vote.getVoteType()).isEqualTo(VoteType.UP);
        assertThat(vote.getCreatedAt()).isEqualTo(NOW);
        assertThat(vote.getUpdatedAt()).isEqualTo(NOW);
        assertThat(vote.isDeleted()).isFalse();
    }

    @Test
    void updateVoteTypeChangesTypeAndTouchesUpdatedAt() {
        Vote vote = Vote.create(POST, USER, VoteType.UP, NOW);
        Instant later = NOW.plusSeconds(30);

        vote.updateVoteType(VoteType.DOWN, later);

        assertThat(vote.getVoteType()).isEqualTo(VoteType.DOWN);
        assertThat(vote.getUpdatedAt()).isEqualTo(later);
    }
}
