package com.mooc.backend.notifications.service;

import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.auth.domain.UserStatus;
import com.mooc.backend.notifications.domain.Notification;
import com.mooc.backend.notifications.domain.NotificationRepository;
import com.mooc.backend.notifications.domain.NotificationType;
import com.mooc.backend.notifications.dto.NotificationListResponse;
import com.mooc.backend.notifications.dto.NotificationResponse;
import com.mooc.backend.notifications.exception.NotificationException;
import com.mooc.backend.posts.domain.Post;
import com.mooc.backend.posts.repository.PostRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 通知业务逻辑单元测试（Task 2.1）。
 *
 * <p>覆盖：生成（含去重刷新）、自互动短路、撤销定位（post / comment 两个作用域）、
 * 已读幂等、他人通知 404（同型错误防枚举）、全部已读、未读计数，
 * 以及列表游标分页（默认 / 上限 size、next_cursor、非法 cursor 400、actor 降级）。
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final UUID RECIPIENT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID POST = UUID.randomUUID();
    private static final UUID COMMENT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PostRepository postRepository;

    @InjectMocks
    private NotificationService notificationService;

    // ---------- onInteraction：生成 / 去重 / 短路 ----------

    @Test
    void onInteractionCreatesNewNotificationForOtherUser() {
        when(notificationRepository.findByRecipientIdAndActorIdAndTypeAndPostIdAndReadAtIsNull(
                RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST)).thenReturn(Optional.empty());

        notificationService.onInteraction(ACTOR, RECIPIENT, NotificationType.POST_LIKED, POST, null, NOW);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getRecipientId()).isEqualTo(RECIPIENT);
        assertThat(saved.getActorId()).isEqualTo(ACTOR);
        assertThat(saved.getType()).isEqualTo(NotificationType.POST_LIKED);
        assertThat(saved.getPostId()).isEqualTo(POST);
        assertThat(saved.getCommentId()).isNull();
        assertThat(saved.getLastInteractedAt()).isEqualTo(NOW);
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void onInteractionCarriesCommentIdForCommentNotifications() {
        when(notificationRepository.findByRecipientIdAndActorIdAndTypeAndPostIdAndReadAtIsNull(
                RECIPIENT, ACTOR, NotificationType.POST_COMMENTED, POST)).thenReturn(Optional.empty());

        notificationService.onInteraction(ACTOR, RECIPIENT, NotificationType.POST_COMMENTED, POST, COMMENT, NOW);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getCommentId()).isEqualTo(COMMENT);
    }

    @Test
    void onInteractionShortCircuitsWhenActorEqualsRecipient() {
        notificationService.onInteraction(ACTOR, ACTOR, NotificationType.POST_LIKED, POST, null, NOW);

        verifyNoInteractions(notificationRepository);
    }

    @Test
    void onInteractionRefreshesExistingUnreadInsteadOfInsert() {
        Notification existing = Notification.create(
                RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null,
                Instant.parse("2026-08-28T09:00:00Z"));
        when(notificationRepository.findByRecipientIdAndActorIdAndTypeAndPostIdAndReadAtIsNull(
                RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST)).thenReturn(Optional.of(existing));

        notificationService.onInteraction(ACTOR, RECIPIENT, NotificationType.POST_LIKED, POST, null, NOW);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        // 同一实例被刷新，而非新增行
        assertThat(captor.getValue()).isSameAs(existing);
        assertThat(captor.getValue().getLastInteractedAt()).isEqualTo(NOW);
        // created_at 保持审计语义不被改写
        assertThat(captor.getValue().getCreatedAt()).isEqualTo(Instant.parse("2026-08-28T09:00:00Z"));
    }

    // ---------- onInteractionRemoved：撤销 ----------

    @Test
    void onInteractionRemovedDeletesUnreadWithPostScopeWhenNoComment() {
        notificationService.onInteractionRemoved(ACTOR, NotificationType.POST_LIKED, POST, null);

        verify(notificationRepository).deleteUnreadPostScoped(ACTOR, NotificationType.POST_LIKED, POST);
        verify(notificationRepository, never()).deleteUnreadCommentScoped(any(), any(), any(), any());
    }

    @Test
    void onInteractionRemovedDeletesUnreadWithCommentScope() {
        notificationService.onInteractionRemoved(ACTOR, NotificationType.POST_COMMENTED, POST, COMMENT);

        verify(notificationRepository).deleteUnreadCommentScoped(
                ACTOR, NotificationType.POST_COMMENTED, POST, COMMENT);
        verify(notificationRepository, never()).deleteUnreadPostScoped(any(), any(), any());
    }

    // ---------- markRead：幂等 / 404 ----------

    @Test
    void markReadStampsUnreadNotificationOfRecipient() {
        Notification n = Notification.create(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, NOW);
        when(notificationRepository.findById(n.getId())).thenReturn(Optional.of(n));
        stubActorAndPostLookups();

        NotificationResponse resp = notificationService.markRead(n.getId(), RECIPIENT, NOW);

        assertThat(resp.getReadAt()).isEqualTo(NOW);
        verify(notificationRepository).save(n);
    }

    @Test
    void markReadIsIdempotentAndDoesNotOverwriteFirstReadAt() {
        Instant firstRead = Instant.parse("2026-08-28T09:30:00Z");
        Notification n = Notification.create(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, NOW);
        n.markRead(firstRead);
        when(notificationRepository.findById(n.getId())).thenReturn(Optional.of(n));
        stubActorAndPostLookups();

        NotificationResponse resp = notificationService.markRead(n.getId(), RECIPIENT, NOW);

        assertThat(resp.getReadAt()).isEqualTo(firstRead);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void markReadReturns404ForForeignNotification() {
        Notification n = Notification.create(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, NOW);
        when(notificationRepository.findById(n.getId())).thenReturn(Optional.of(n));

        assertThatThrownBy(() -> notificationService.markRead(n.getId(), UUID.randomUUID(), NOW))
                .isInstanceOf(NotificationException.class)
                .hasFieldOrPropertyWithValue("status", org.springframework.http.HttpStatus.NOT_FOUND)
                .hasFieldOrPropertyWithValue("code", "NOTIFICATION_NOT_FOUND");
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void markReadReturns404ForMissingNotification() {
        UUID missing = UUID.randomUUID();
        when(notificationRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.markRead(missing, RECIPIENT, NOW))
                .isInstanceOf(NotificationException.class)
                .hasFieldOrPropertyWithValue("status", org.springframework.http.HttpStatus.NOT_FOUND);
    }

    // ---------- unread-count / read-all ----------

    @Test
    void unreadCountDelegatesToRepository() {
        when(notificationRepository.countByRecipientIdAndReadAtIsNull(RECIPIENT)).thenReturn(7L);

        assertThat(notificationService.unreadCount(RECIPIENT)).isEqualTo(7L);
    }

    @Test
    void markAllReadDelegatesToRepository() {
        notificationService.markAllRead(RECIPIENT, NOW);

        verify(notificationRepository).markAllRead(RECIPIENT, NOW);
    }

    // ---------- listNotifications：游标分页 ----------

    @Test
    void listDefaultsToPageSize20AndFetchesOneExtraForHasMore() {
        List<Notification> rows = List.of(
                Notification.create(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, NOW));
        when(notificationRepository.findPage(eq(RECIPIENT), eq(false), eq(21), eq(null), eq(null), eq(false)))
                .thenReturn(rows);
        stubActorAndPostLookups();

        NotificationListResponse resp = notificationService.listNotifications(RECIPIENT, null, null, null);

        assertThat(resp.getItems()).hasSize(1);
        assertThat(resp.isHasMore()).isFalse();
        assertThat(resp.getNextCursor()).isNull();
    }

    @Test
    void listClampsSizeToMaximum50() {
        when(notificationRepository.findPage(eq(RECIPIENT), eq(false), eq(51), eq(null), eq(null), eq(false)))
                .thenReturn(List.of());

        notificationService.listNotifications(RECIPIENT, null, 500, null);

        verify(notificationRepository).findPage(RECIPIENT, false, 51, null, null, false);
    }

    @Test
    void listPassesUnreadOnlyFlag() {
        when(notificationRepository.findPage(eq(RECIPIENT), eq(true), eq(21), eq(null), eq(null), eq(false)))
                .thenReturn(List.of());

        notificationService.listNotifications(RECIPIENT, null, null, Boolean.TRUE);

        verify(notificationRepository).findPage(RECIPIENT, true, 21, null, null, false);
    }

    @Test
    void listBuildsNextCursorFromLastRowWhenMoreAvailable() {
        Notification newer = Notification.create(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, NOW.plusSeconds(60));
        Notification older = Notification.create(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, NOW);
        List<Notification> rows = List.of(newer, older);
        when(notificationRepository.findPage(eq(RECIPIENT), eq(false), eq(2), eq(null), eq(null), eq(false)))
                .thenReturn(rows);
        stubActorAndPostLookups();

        NotificationListResponse resp = notificationService.listNotifications(RECIPIENT, null, 1, null);

        assertThat(resp.isHasMore()).isTrue();
        assertThat(resp.getItems()).hasSize(1);
        // next_cursor 指向当前页最后一行（newer）：下一页按 (ts, id) 严格小于它截断
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (newer.getLastInteractedAt().toString() + "|" + newer.getId())
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(resp.getNextCursor()).isEqualTo(expected);
    }

    @Test
    void listRejectsMalformedCursorAs400() {
        assertThatThrownBy(() -> notificationService.listNotifications(
                RECIPIENT, "not-a-cursor", null, null))
                .isInstanceOf(NotificationException.class)
                .hasFieldOrPropertyWithValue("status", org.springframework.http.HttpStatus.BAD_REQUEST)
                .hasFieldOrPropertyWithValue("code", "VALIDATION_FAILED");
        verify(notificationRepository, never())
                .findPage(any(), anyBoolean(), org.mockito.ArgumentMatchers.anyInt(), any(), any(), anyBoolean());
    }

    @Test
    void listContinuesFromCursorWithoutOverlap() {
        Notification older = Notification.create(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, NOW);
        String cursor = encodeCursor(older.getLastInteractedAt(), older.getId());
        when(notificationRepository.findPage(eq(RECIPIENT), eq(false), eq(21), eq(older.getLastInteractedAt()),
                eq(older.getId()), eq(true))).thenReturn(List.of());

        NotificationListResponse resp = notificationService.listNotifications(RECIPIENT, cursor, null, null);

        assertThat(resp.getItems()).isEmpty();
        assertThat(resp.isHasMore()).isFalse();
    }

    // ---------- 列表项组装：actor join / 降级 / 帖子标题 ----------

    @Test
    void listMapsActorIdentityAndPostTitle() {
        Notification n = Notification.create(RECIPIENT, ACTOR, NotificationType.POST_COMMENTED, POST, COMMENT, NOW);
        when(notificationRepository.findPage(eq(RECIPIENT), eq(false), eq(21), eq(null), eq(null), eq(false)))
                .thenReturn(List.of(n));
        stubActorAndPostLookups();

        NotificationListResponse resp = notificationService.listNotifications(RECIPIENT, null, null, null);

        NotificationResponse item = resp.getItems().get(0);
        assertThat(item.getId()).isEqualTo(n.getId());
        assertThat(item.getType()).isEqualTo("POST_COMMENTED");
        assertThat(item.getActor().id()).isEqualTo(ACTOR);
        assertThat(item.getActor().displayName()).isEqualTo("Alice");
        assertThat(item.getActor().avatarUrl()).isEqualTo("https://cdn.example.com/a.png");
        assertThat(item.getPost().id()).isEqualTo(POST);
        assertThat(item.getPost().title()).isEqualTo("Trip");
        assertThat(item.getCommentId()).isEqualTo(COMMENT);
        assertThat(item.getReadAt()).isNull();
        assertThat(item.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void listDegradesDeletedOrMissingActorButKeepsEntry() {
        Notification byDeleted = Notification.create(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, NOW);
        UUID missingActor = UUID.randomUUID();
        Notification byMissing = Notification.create(RECIPIENT, missingActor, NotificationType.POST_LIKED, POST, null, NOW);
        when(notificationRepository.findPage(eq(RECIPIENT), eq(false), eq(21), eq(null), eq(null), eq(false)))
                .thenReturn(List.of(byDeleted, byMissing));

        User deleted = mock(User.class);
        when(deleted.getId()).thenReturn(ACTOR);
        when(deleted.getStatus()).thenReturn(UserStatus.DELETED);
        when(userRepository.findAllById(any())).thenReturn(List.of(deleted)); // missingActor 查不到
        when(postRepository.findAllById(any())).thenReturn(List.of());

        NotificationListResponse resp = notificationService.listNotifications(RECIPIENT, null, null, null);

        assertThat(resp.getItems()).hasSize(2);
        // 软删 actor：条目保留，身份降级为 null（前端以占位头像与 "A traveler" 展示）
        assertThat(resp.getItems().get(0).getActor().id()).isEqualTo(ACTOR);
        assertThat(resp.getItems().get(0).getActor().displayName()).isNull();
        assertThat(resp.getItems().get(0).getActor().avatarUrl()).isNull();
        assertThat(resp.getItems().get(1).getActor().id()).isEqualTo(missingActor);
        assertThat(resp.getItems().get(1).getActor().displayName()).isNull();
        // 帖子行缺失：post 引用保留 id，title 为 null（前端跳转后按 404 处理）
        assertThat(resp.getItems().get(0).getPost().id()).isEqualTo(POST);
        assertThat(resp.getItems().get(0).getPost().title()).isNull();
    }

    @Test
    void listKeepsEntryForSoftDeletedPostWithTitle() {
        Notification n = Notification.create(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, NOW);
        when(notificationRepository.findPage(eq(RECIPIENT), eq(false), eq(21), eq(null), eq(null), eq(false)))
                .thenReturn(List.of(n));

        Post deletedPost = mock(Post.class);
        when(deletedPost.getId()).thenReturn(POST);
        when(deletedPost.getTitle()).thenReturn("Deleted Trip");
        when(userRepository.findAllById(any())).thenReturn(List.of());
        when(postRepository.findAllById(any())).thenReturn(List.of(deletedPost));

        NotificationListResponse resp = notificationService.listNotifications(RECIPIENT, null, null, null);

        // 目标帖子已软删：通知条目保留（不级联删除），标题照常返回，跳转由详情端点报 404
        assertThat(resp.getItems()).hasSize(1);
        assertThat(resp.getItems().get(0).getPost().title()).isEqualTo("Deleted Trip");
    }

    // ---------- helpers ----------

    /**
     * stub actor / post 批量解析（toResponses 走 findAllById + toMap(User::getId)）。
     * mock 实体必须 stub getId()，否则 toMap 的 key 为 null，身份解析永远落空。
     */
    private void stubActorAndPostLookups() {
        User actor = mock(User.class);
        when(actor.getId()).thenReturn(ACTOR);
        when(actor.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(actor.getDisplayName()).thenReturn("Alice");
        when(actor.getAvatarUrl()).thenReturn("https://cdn.example.com/a.png");
        when(userRepository.findAllById(any())).thenReturn(List.of(actor));

        Post post = mock(Post.class);
        when(post.getId()).thenReturn(POST);
        when(post.getTitle()).thenReturn("Trip");
        when(postRepository.findAllById(any())).thenReturn(List.of(post));
    }

    private String encodeCursor(Instant ts, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (ts.toString() + "|" + id).getBytes(StandardCharsets.UTF_8));
    }
}
