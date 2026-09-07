package com.mooc.backend.repository;
import com.mooc.backend.entity.Notification;
import com.mooc.backend.entity.NotificationType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 通知仓储（Task 1.1）。
 *
 * <p>去重查询命中索引 {@code (recipient_id, read_at)}（recipient 前缀 + read_at 过滤）；
 * 未读计数同型。撤销与批量已读用 bulk JPQL（{@code @Modifying}），并开启
 * {@code clearAutomatically / flushAutomatically} 防止持久化上下文读到过期快照。
 * 所有写语义都以「未读 = read_at IS NULL」为界：已读行撤销时不删、批量已读时不覆盖。
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID>, NotificationRepositoryCustom {

    /**
     * 去重定位：同 recipient + actor + type + post 且<b>未读</b>的既有通知。
     * 命中则由调用方刷新其 last_interacted_at，而非新增行（design.md 去重规则）。
     */
    Optional<Notification> findByRecipientIdAndActorIdAndTypeAndPostIdAndReadAtIsNull(
            UUID recipientId, UUID actorId, NotificationType type, UUID postId);

    /** 未读通知计数（badge 数据源），走索引 {@code (recipient_id, read_at)}。 */
    long countByRecipientIdAndReadAtIsNull(UUID recipientId);

    /**
     * 撤销互动产生的<b>未读</b>通知（点赞 / 收藏类，无评论维度）。
     * 返回删除行数；已读通知永不撤销。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            DELETE FROM Notification n
            WHERE n.actorId = :actorId AND n.type = :type AND n.postId = :postId
              AND n.commentId IS NULL AND n.readAt IS NULL
            """)
    int deleteUnreadPostScoped(@Param("actorId") UUID actorId,
                               @Param("type") NotificationType type,
                               @Param("postId") UUID postId);

    /**
     * 撤销互动产生的<b>未读</b>通知（评论类，精确到目标评论）。
     * 顶层评论级联软删时，由调用方按每条被删评论逐次调用。已读通知永不撤销。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            DELETE FROM Notification n
            WHERE n.actorId = :actorId AND n.type = :type AND n.postId = :postId
              AND n.commentId = :commentId AND n.readAt IS NULL
            """)
    int deleteUnreadCommentScoped(@Param("actorId") UUID actorId,
                                  @Param("type") NotificationType type,
                                  @Param("postId") UUID postId,
                                  @Param("commentId") UUID commentId);

    /** 全部标记已读：仅写当前用户未读行的 read_at，既有已读行的 read_at 不被覆盖。返回更新行数。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Notification n
            SET n.readAt = :now, n.updatedAt = :now
            WHERE n.recipientId = :recipientId AND n.readAt IS NULL
            """)
    int markAllRead(@Param("recipientId") UUID recipientId, @Param("now") Instant now);

    /** 取某用户全部未读通知（生成侧辅助 / 测试与排查用）。 */
    List<Notification> findByRecipientIdAndReadAtIsNull(UUID recipientId);
}
