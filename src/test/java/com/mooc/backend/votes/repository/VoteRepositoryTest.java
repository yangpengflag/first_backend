package com.mooc.backend.votes.repository;

import com.mooc.backend.votes.domain.Vote;
import com.mooc.backend.votes.domain.VoteType;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 仓储层测试：验证一人一票唯一约束拒绝重复行，以及按类型计数。
 */
@SpringBootTest
@Transactional
class VoteRepositoryTest {

    @Autowired
    private VoteRepository voteRepository;

    private static final Instant NOW = Instant.now();

    @Test
    void uniqueConstraintRejectsDuplicatePostUserPair() {
        UUID postId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        voteRepository.saveAndFlush(Vote.create(postId, userId, VoteType.UP, NOW));

        assertThatThrownBy(() -> voteRepository.saveAndFlush(Vote.create(postId, userId, VoteType.DOWN, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void countByVoteTypeIsCorrect() {
        UUID postId = UUID.randomUUID();
        voteRepository.saveAndFlush(Vote.create(postId, UUID.randomUUID(), VoteType.UP, NOW));
        voteRepository.saveAndFlush(Vote.create(postId, UUID.randomUUID(), VoteType.UP, NOW));
        voteRepository.saveAndFlush(Vote.create(postId, UUID.randomUUID(), VoteType.DOWN, NOW));

        assertThat(voteRepository.countByPostIdAndVoteType(postId, VoteType.UP)).isEqualTo(2);
        assertThat(voteRepository.countByPostIdAndVoteType(postId, VoteType.DOWN)).isEqualTo(1);
    }

    @Test
    void physicalDeleteFreesUniqueSlot() {
        UUID postId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Vote vote = voteRepository.saveAndFlush(Vote.create(postId, userId, VoteType.UP, NOW));
        voteRepository.delete(vote);
        voteRepository.flush();

        // 删除后应可重新投票
        Vote reVote = voteRepository.saveAndFlush(Vote.create(postId, userId, VoteType.DOWN, NOW));
        assertThat(reVote.getId()).isNotNull();
    }
}
