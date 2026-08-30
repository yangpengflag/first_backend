package com.mooc.backend.votes.service;

import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.posts.domain.Post;
import com.mooc.backend.posts.repository.PostRepository;
import com.mooc.backend.votes.api.VoteResponse;
import com.mooc.backend.votes.api.VoteStatsResponse;
import com.mooc.backend.votes.domain.Vote;
import com.mooc.backend.votes.domain.VoteType;
import com.mooc.backend.votes.exception.VoteException;
import com.mooc.backend.votes.repository.VoteRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoteServiceTest {

    @Mock
    private VoteRepository voteRepository;

    @Mock
    private PostRepository postRepository;

    @InjectMocks
    private VoteService voteService;

    private static final UUID POST = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");

    @Test
    void voteCreateWhenNoneExists() {
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(mock(Post.class)));
        when(voteRepository.findByPostIdAndUserId(POST, USER)).thenReturn(Optional.empty());
        when(voteRepository.saveAndFlush(any(Vote.class))).thenAnswer(inv -> inv.getArgument(0));

        VoteResponse resp = voteService.vote(POST, USER, VoteType.UP, NOW);

        assertThat(resp.getPostId()).isEqualTo(POST);
        assertThat(resp.getUserVote()).isEqualTo("UP");
    }

    @Test
    void voteSameTypeCancels() {
        Vote existing = Vote.create(POST, USER, VoteType.UP, NOW);
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(mock(Post.class)));
        when(voteRepository.findByPostIdAndUserId(POST, USER)).thenReturn(Optional.of(existing));

        VoteResponse resp = voteService.vote(POST, USER, VoteType.UP, NOW);

        assertThat(resp.getUserVote()).isNull();
        verify(voteRepository).delete(existing);
    }

    @Test
    void voteDifferentTypeSwitches() {
        Vote existing = Vote.create(POST, USER, VoteType.UP, NOW);
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(mock(Post.class)));
        when(voteRepository.findByPostIdAndUserId(POST, USER)).thenReturn(Optional.of(existing));
        when(voteRepository.save(any(Vote.class))).thenAnswer(inv -> inv.getArgument(0));

        VoteResponse resp = voteService.vote(POST, USER, VoteType.DOWN, NOW);

        assertThat(resp.getUserVote()).isEqualTo("DOWN");
        assertThat(existing.getVoteType()).isEqualTo(VoteType.DOWN);
        verify(voteRepository, never()).delete(any());
    }

    @Test
    void votePostNotFound() {
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> voteService.vote(POST, USER, VoteType.UP, NOW))
                .isInstanceOf(VoteException.class);
    }

    @Test
    void getVoteStatsAggregatesAndFlagsUserVote() {
        Vote myVote = Vote.create(POST, USER, VoteType.DOWN, NOW);
        when(voteRepository.countByPostIdAndVoteType(POST, VoteType.UP)).thenReturn(5);
        when(voteRepository.countByPostIdAndVoteType(POST, VoteType.DOWN)).thenReturn(2);
        when(voteRepository.findByPostIdAndUserId(POST, USER)).thenReturn(Optional.of(myVote));

        VoteStatsResponse stats = voteService.getVoteStats(POST, USER);

        assertThat(stats.getUpCount()).isEqualTo(5);
        assertThat(stats.getDownCount()).isEqualTo(2);
        assertThat(stats.getUserVote()).isEqualTo("DOWN");
    }

    @Test
    void concurrentCreateHitsUniqueConstraintThenReReads() {
        Vote existing = Vote.create(POST, USER, VoteType.UP, NOW);
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(mock(Post.class)));
        when(voteRepository.findByPostIdAndUserId(POST, USER))
                .thenReturn(Optional.empty())          // 首次：判定为新建
                .thenReturn(Optional.of(existing));     // DVI 后重读
        when(voteRepository.saveAndFlush(any(Vote.class)))
                .thenThrow(new DataIntegrityViolationException("dup"));

        VoteResponse resp = voteService.vote(POST, USER, VoteType.UP, NOW);

        assertThat(resp.getUserVote()).isEqualTo("UP");
    }
}
