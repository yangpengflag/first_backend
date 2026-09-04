package com.mooc.backend.votes.service;

import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.notifications.domain.NotificationType;
import com.mooc.backend.notifications.service.NotificationService;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoteServiceTest {

    @Mock
    private VoteRepository voteRepository;

    @Mock
    private PostRepository postRepository;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private VoteService voteService;

    private static final UUID POST = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID AUTHOR = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");

    @Test
    void voteCreateWhenNoneExists() {
        Post post = postWithAuthor(AUTHOR);
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(post));
        when(voteRepository.findByPostIdAndUserId(POST, USER)).thenReturn(Optional.empty());
        when(voteRepository.saveAndFlush(any(Vote.class))).thenAnswer(inv -> inv.getArgument(0));

        VoteResponse resp = voteService.vote(POST, USER, VoteType.UP, NOW);

        assertThat(resp.getPostId()).isEqualTo(POST);
        assertThat(resp.getUserVote()).isEqualTo("UP");
    }

    @Test
    void voteSameTypeCancels() {
        Vote existing = Vote.create(POST, USER, VoteType.UP, NOW);
        Post post = postWithAuthor(AUTHOR);
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(post));
        when(voteRepository.findByPostIdAndUserId(POST, USER)).thenReturn(Optional.of(existing));

        VoteResponse resp = voteService.vote(POST, USER, VoteType.UP, NOW);

        assertThat(resp.getUserVote()).isNull();
        verify(voteRepository).delete(existing);
    }

    @Test
    void voteDifferentTypeSwitches() {
        Vote existing = Vote.create(POST, USER, VoteType.UP, NOW);
        Post post = postWithAuthor(AUTHOR);
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(post));
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
        Post post = postWithAuthor(AUTHOR);
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(post));
        when(voteRepository.findByPostIdAndUserId(POST, USER))
                .thenReturn(Optional.empty())          // 首次：判定为新建
                .thenReturn(Optional.of(existing));     // DVI 后重读
        when(voteRepository.saveAndFlush(any(Vote.class)))
                .thenThrow(new DataIntegrityViolationException("dup"));

        VoteResponse resp = voteService.vote(POST, USER, VoteType.UP, NOW);

        assertThat(resp.getUserVote()).isEqualTo("UP");
    }

    // ---------- 通知挂接（Task 2.3）：UP 成功生成 / DOWN 短路 / 撤销 / 切换 ----------

    @Test
    void upVoteCreateNotifiesPostAuthor() {
        Post post = postWithAuthor(AUTHOR);
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(post));
        when(voteRepository.findByPostIdAndUserId(POST, USER)).thenReturn(Optional.empty());
        when(voteRepository.saveAndFlush(any(Vote.class))).thenAnswer(inv -> inv.getArgument(0));

        voteService.vote(POST, USER, VoteType.UP, NOW);

        verify(notificationService).onInteraction(
                USER, AUTHOR, NotificationType.POST_LIKED, POST, null, NOW);
    }

    @Test
    void downVoteCreateDoesNotNotify() {
        Post post = postWithAuthor(AUTHOR);
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(post));
        when(voteRepository.findByPostIdAndUserId(POST, USER)).thenReturn(Optional.empty());
        when(voteRepository.saveAndFlush(any(Vote.class))).thenAnswer(inv -> inv.getArgument(0));

        voteService.vote(POST, USER, VoteType.DOWN, NOW);

        verifyNoInteractions(notificationService);
    }

    @Test
    void selfUpVoteDoesNotNotifyRecipientOtherThanShortCircuitInsideNotificationService() {
        // 自投：VoteService 无脑下发，actor == recipient 的短路由 NotificationService 负责
        Post post = postWithAuthor(USER);
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(post));
        when(voteRepository.findByPostIdAndUserId(POST, USER)).thenReturn(Optional.empty());
        when(voteRepository.saveAndFlush(any(Vote.class))).thenAnswer(inv -> inv.getArgument(0));

        voteService.vote(POST, USER, VoteType.UP, NOW);

        verify(notificationService).onInteraction(
                USER, USER, NotificationType.POST_LIKED, POST, null, NOW);
    }

    @Test
    void sameTypeUpCancelRevokesUnreadNotification() {
        Vote existing = Vote.create(POST, USER, VoteType.UP, NOW);
        Post post = postWithAuthor(AUTHOR);
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(post));
        when(voteRepository.findByPostIdAndUserId(POST, USER)).thenReturn(Optional.of(existing));

        voteService.vote(POST, USER, VoteType.UP, NOW);

        verify(notificationService).onInteractionRemoved(
                USER, NotificationType.POST_LIKED, POST, null);
    }

    @Test
    void sameTypeDownCancelDoesNotTouchNotifications() {
        Vote existing = Vote.create(POST, USER, VoteType.DOWN, NOW);
        Post post = postWithAuthor(AUTHOR);
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(post));
        when(voteRepository.findByPostIdAndUserId(POST, USER)).thenReturn(Optional.of(existing));

        voteService.vote(POST, USER, VoteType.DOWN, NOW);

        // DOWN 从未产生通知，取消时也无通知可撤
        verifyNoInteractions(notificationService);
    }

    @Test
    void switchUpToDownRevokesUnreadNotification() {
        Vote existing = Vote.create(POST, USER, VoteType.UP, NOW);
        Post post = postWithAuthor(AUTHOR);
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(post));
        when(voteRepository.findByPostIdAndUserId(POST, USER)).thenReturn(Optional.of(existing));
        when(voteRepository.save(any(Vote.class))).thenAnswer(inv -> inv.getArgument(0));

        voteService.vote(POST, USER, VoteType.DOWN, NOW);

        // UP 不复存在（切为 DOWN）：语义同构于取消该 UP 的未读通知
        verify(notificationService).onInteractionRemoved(
                USER, NotificationType.POST_LIKED, POST, null);
    }

    @Test
    void switchDownToUpGeneratesNotification() {
        Vote existing = Vote.create(POST, USER, VoteType.DOWN, NOW);
        Post post = postWithAuthor(AUTHOR);
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(post));
        when(voteRepository.findByPostIdAndUserId(POST, USER)).thenReturn(Optional.of(existing));
        when(voteRepository.save(any(Vote.class))).thenAnswer(inv -> inv.getArgument(0));

        voteService.vote(POST, USER, VoteType.UP, NOW);

        verify(notificationService).onInteraction(
                USER, AUTHOR, NotificationType.POST_LIKED, POST, null, NOW);
    }

    @Test
    void concurrentCreateDviFallbackDoesNotNotify() {
        // DVI 兜底：本线程未成功创建（并发线程已建），通知由成功线程负责，本线程不再下发
        Vote existing = Vote.create(POST, USER, VoteType.UP, NOW);
        Post post = postWithAuthor(AUTHOR);
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(post));
        when(voteRepository.findByPostIdAndUserId(POST, USER))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(voteRepository.saveAndFlush(any(Vote.class)))
                .thenThrow(new DataIntegrityViolationException("dup"));

        voteService.vote(POST, USER, VoteType.UP, NOW);

        verifyNoInteractions(notificationService);
    }

    private Post postWithAuthor(UUID authorId) {
        Post post = mock(Post.class);
        when(post.getAuthorId()).thenReturn(authorId);
        return post;
    }
}
