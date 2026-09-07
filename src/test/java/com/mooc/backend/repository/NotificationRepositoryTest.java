package com.mooc.backend.repository;
import com.mooc.backend.entity.Notification;
import com.mooc.backend.repository.NotificationRepository;
import com.mooc.backend.entity.NotificationType;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知仓储层测试（Task 1.1）。
 *
 * <p>覆盖去重查询（同 recipient+actor+type+post 且未读）、未读计数、
 * 撤销删除（仅删未读行，已读保留）、批量已读，以及游标分页查询
 * （按 last_interacted_at 倒序、id 字节序 tie-break、unreadOnly 过滤）。
 *
 * <p>整个测试运行在 {@code @Transactional} 下，DELETE 在事务结束时回滚，不污染库。
 * bulk JPQL（delete / update）绕过持久化上下文，断言前统一 {@code entityManager.clear()}。
 */
@SpringBootTest
@Transactional
class NotificationRepositoryTest {

    private static final UUID RECIPIENT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID OTHER_ACTOR = UUID.randomUUID();
    private static final UUID POST = UUID.randomUUID();
    private static final UUID OTHER_POST = UUID.randomUUID();
    private static final UUID COMMENT_1 = UUID.randomUUID();
    private static final UUID COMMENT_2 = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-08-28T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-08-28T11:00:00Z");
    private static final Instant T2 = Instant.parse("2026-08-28T12:00:00Z");
    private static final Instant T3 = Instant.parse("2026-08-28T13:00:00Z");
    private static final Instant T4 = Instant.parse("2026-08-28T14:00:00Z");
    private static final Instant T5 = Instant.parse("2026-08-28T15:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-28T16:00:00Z");

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void cleanNotificationsTable() {
        entityManager.createNativeQuery("DELETE FROM notifications").executeUpdate();
    }

    // ---------- 去重查询 ----------

    @Test
    void dedupQueryReturnsOnlyUnreadRowOfSameActorTypeAndPost() {
        Notification unread = unread(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, T2);
        read(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, T1, T1);
        unread(RECIPIENT, OTHER_ACTOR, NotificationType.POST_LIKED, POST, null, T3);
        unread(RECIPIENT, ACTOR, NotificationType.POST_LIKED, OTHER_POST, null, T3);

        var hit = notificationRepository
                .findByRecipientIdAndActorIdAndTypeAndPostIdAndReadAtIsNull(
                        RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST);

        assertThat(hit).isPresent();
        assertThat(hit.orElseThrow().getId()).isEqualTo(unread.getId());
    }

    @Test
    void dedupQueryReturnsEmptyWhenNoUnreadRowMatches() {
        read(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, T1, T1);

        var hit = notificationRepository
                .findByRecipientIdAndActorIdAndTypeAndPostIdAndReadAtIsNull(
                        RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST);

        assertThat(hit).isEmpty();
    }

    // ---------- 未读计数 ----------

    @Test
    void unreadCountCountsOnlyUnreadRowsOfRecipient() {
        unread(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, T1);
        unread(RECIPIENT, ACTOR, NotificationType.POST_COMMENTED, POST, COMMENT_1, T2);
        read(RECIPIENT, ACTOR, NotificationType.POST_BOOKMARKED, POST, null, T3, T4);
        unread(OTHER_ACTOR, ACTOR, NotificationType.POST_LIKED, POST, null, T1);

        assertThat(notificationRepository.countByRecipientIdAndReadAtIsNull(RECIPIENT)).isEqualTo(2L);
        assertThat(notificationRepository.countByRecipientIdAndReadAtIsNull(OTHER_ACTOR)).isEqualTo(1L);
    }

    // ---------- 撤销删除（已读永不撤销） ----------

    @Test
    void deleteUnreadPostScopedRemovesOnlyUnreadRows() {
        read(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, T1, T2);
        unread(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, T3);
        unread(RECIPIENT, ACTOR, NotificationType.POST_COMMENTED, POST, COMMENT_1, T3);
        unread(RECIPIENT, OTHER_ACTOR, NotificationType.POST_LIKED, POST, null, T3);

        int removed = notificationRepository.deleteUnreadPostScoped(
                ACTOR, NotificationType.POST_LIKED, POST);
        entityManager.clear();

        assertThat(removed).isEqualTo(1);
        List<Notification> remaining = notificationRepository.findAll();
        assertThat(remaining).hasSize(3);
        // 已读行必须保留
        assertThat(remaining).anySatisfy(n -> {
            assertThat(n.isRead()).isTrue();
            assertThat(n.getActorId()).isEqualTo(ACTOR);
            assertThat(n.getType()).isEqualTo(NotificationType.POST_LIKED);
        });
    }

    @Test
    void deleteUnreadCommentScopedRemovesOnlyTargetCommentRow() {
        Notification targetUnread = unread(RECIPIENT, ACTOR, NotificationType.POST_COMMENTED, POST, COMMENT_1, T1);
        Notification otherCommentUnread = unread(RECIPIENT, ACTOR, NotificationType.POST_COMMENTED, POST, COMMENT_2, T2);
        Notification readRow = read(RECIPIENT, ACTOR, NotificationType.POST_COMMENTED, POST, COMMENT_1, T1, T2);

        int removed = notificationRepository.deleteUnreadCommentScoped(
                ACTOR, NotificationType.POST_COMMENTED, POST, COMMENT_1);
        entityManager.clear();

        assertThat(removed).isEqualTo(1);
        assertThat(notificationRepository.findById(targetUnread.getId())).isEmpty();
        assertThat(notificationRepository.findById(otherCommentUnread.getId())).isPresent();
        assertThat(notificationRepository.findById(readRow.getId())).isPresent();
    }

    // ---------- 批量已读 ----------

    @Test
    void markAllReadStampsOnlyUnreadRowsAndKeepsFirstReadAt() {
        Notification firstRead = read(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, T1, T0);
        Notification unreadA = unread(RECIPIENT, ACTOR, NotificationType.POST_COMMENTED, POST, COMMENT_1, T2);
        Notification unreadB = unread(RECIPIENT, OTHER_ACTOR, NotificationType.POST_BOOKMARKED, OTHER_POST, null, T3);
        unread(OTHER_ACTOR, ACTOR, NotificationType.POST_LIKED, POST, null, T1);

        int updated = notificationRepository.markAllRead(RECIPIENT, NOW);
        entityManager.clear();

        assertThat(updated).isEqualTo(2);
        assertThat(notificationRepository.findById(unreadA.getId()).orElseThrow().getReadAt()).isEqualTo(NOW);
        assertThat(notificationRepository.findById(unreadB.getId()).orElseThrow().getReadAt()).isEqualTo(NOW);
        // 既有已读行的 read_at 不被覆盖（幂等语义）
        assertThat(notificationRepository.findById(firstRead.getId()).orElseThrow().getReadAt()).isEqualTo(T0);
    }

    // ---------- 游标分页查询 ----------

    @Test
    void findPageOrdersByLastInteractedAtDesc() {
        unread(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, T1);
        Notification t4 = unread(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, T4);
        Notification t5 = unread(RECIPIENT, ACTOR, NotificationType.POST_COMMENTED, POST, COMMENT_1, T5);
        Notification t3 = unread(RECIPIENT, ACTOR, NotificationType.POST_BOOKMARKED, POST, null, T3);
        read(RECIPIENT, ACTOR, NotificationType.POST_LIKED, OTHER_POST, null, T2, T2);

        List<Notification> page = notificationRepository.findPage(RECIPIENT, false, 3, null, null, false);

        assertThat(page).extracting(Notification::getId)
                .containsExactly(t5.getId(), t4.getId(), t3.getId());
    }

    @Test
    void findPageUnreadOnlyExcludesReadRows() {
        unread(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, T1);
        read(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, T2, T2);

        List<Notification> page = notificationRepository.findPage(RECIPIENT, true, 10, null, null, false);

        assertThat(page).hasSize(1);
        assertThat(page.get(0).getReadAt()).isNull();
    }

    @Test
    void findPageCursorContinuesWithoutOverlap() {
        Notification t1 = unread(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, T1);
        Notification t2 = unread(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, T2);
        Notification t3 = unread(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, T3);
        unread(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, T4);
        unread(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, T5);

        List<Notification> firstPage = notificationRepository.findPage(RECIPIENT, false, 3, null, null, false);
        assertThat(firstPage).hasSize(3);

        Notification last = firstPage.get(firstPage.size() - 1);
        assertThat(last.getId()).isEqualTo(t3.getId());

        List<Notification> nextPage = notificationRepository.findPage(
                RECIPIENT, false, 3, last.getLastInteractedAt(), last.getId(), true);

        assertThat(nextPage).extracting(Notification::getId)
                .containsExactly(t2.getId(), t1.getId());
    }

    @Test
    void findPageCursorTieBreaksByIdWithoutOverlap() {
        Instant sameTs = T3;
        Notification first = unread(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, sameTs);
        Notification second = unread(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, sameTs);
        assertThat(first.getId()).isNotEqualTo(second.getId());

        List<Notification> page = notificationRepository.findPage(RECIPIENT, false, 1, null, null, false);
        assertThat(page).hasSize(1);
        Notification head = page.get(0);

        List<Notification> rest = notificationRepository.findPage(
                RECIPIENT, false, 10, head.getLastInteractedAt(), head.getId(), true);

        assertThat(rest).hasSize(1);
        assertThat(rest.get(0).getId()).isNotEqualTo(head.getId());
        // 两行合计恰为全集，无遗漏无重叠
        assertThat(rest.get(0).getId()).isIn(first.getId(), second.getId());
    }

    @Test
    void findPageIsolatesRecipient() {
        unread(RECIPIENT, ACTOR, NotificationType.POST_LIKED, POST, null, T2);
        unread(OTHER_ACTOR, ACTOR, NotificationType.POST_LIKED, POST, null, T5);

        List<Notification> page = notificationRepository.findPage(RECIPIENT, false, 10, null, null, false);

        assertThat(page).hasSize(1);
        assertThat(page.get(0).getRecipientId()).isEqualTo(RECIPIENT);
    }

    // ---------- helpers ----------

    private Notification unread(UUID recipient, UUID actor, NotificationType type,
                                UUID post, UUID comment, Instant lastInteractedAt) {
        Notification n = Notification.create(recipient, actor, type, post, comment, lastInteractedAt);
        return notificationRepository.saveAndFlush(n);
    }

    private Notification read(UUID recipient, UUID actor, NotificationType type,
                              UUID post, UUID comment, Instant lastInteractedAt, Instant readAt) {
        Notification n = Notification.create(recipient, actor, type, post, comment, lastInteractedAt);
        n.markRead(readAt);
        return notificationRepository.saveAndFlush(n);
    }
}
