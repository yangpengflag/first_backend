package com.mooc.backend.service;

import com.mooc.backend.entity.User;
import com.mooc.backend.repository.UserRepository;
import com.mooc.backend.entity.UserStatus;
import com.mooc.backend.entity.Notification;
import com.mooc.backend.repository.NotificationRepository;
import com.mooc.backend.entity.NotificationType;
import com.mooc.backend.dto.response.NotificationListResponse;
import com.mooc.backend.dto.response.NotificationResponse;
import com.mooc.backend.exception.NotificationException;
import com.mooc.backend.entity.Post;
import com.mooc.backend.repository.PostRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 站内通知业务逻辑（Task 2.2 / 3.2）。
 *
 * <p><b>生成与撤销（挂接侧同事务直调）</b>：{@code VoteService} / {@code CommentService} /
 * {@code BookmarkService} 在互动写路径成功后调用 {@link #onInteraction} /
 * {@link #onInteractionRemoved}，与本模块零反向依赖（挂接侧单向下发，无循环依赖）。
 * <ul>
 *   <li>生成：自互动短路（actor == recipient）；命中「同 recipient + actor + type + post
 *       且未读」则刷新 {@code last_interacted_at}，否则插入新行。DOWN 投票不产生通知，
 *       由挂接侧（仅在 UP 成功路径调用）保证。</li>
 *   <li>撤销：按 actor + type + post（评论类加 comment）删除<b>未读</b>行；已读永不撤销。
 *       评论软删时由调用方按每条被删评论逐次调用（含顶层级联的回复）。</li>
 * </ul>
 *
 * <p><b>列表读取</b>：按 {@code last_interacted_at} 倒序游标分页（(ts, id) 字节序 tie-break，
 * 与 posts cursor 惯例同构）；actor 身份批量 IN 解析（join 语义，无 N+1），软删 / 缺失
 * actor 降级为 null 身份（条目照常返回，前端占位展示）。
 */
@Service
public class NotificationService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final PostRepository postRepository;

    public NotificationService(NotificationRepository notificationRepository,
                               UserRepository userRepository,
                               PostRepository postRepository) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.postRepository = postRepository;
    }

    // ---------- 生成与撤销（挂接侧同事务直调） ----------

    /**
     * 记录一次互动通知：自互动短路；未读去重命中则刷新最后互动时间，否则插入新行。
     * 运行于调用方事务内（{@code REQUIRED} 传播加入外层），生成与互动同事务一致。
     */
    @Transactional
    public void onInteraction(UUID actorId, UUID recipientId, NotificationType type,
                              UUID postId, UUID commentId, Instant now) {
        if (actorId.equals(recipientId)) {
            return;
        }
        notificationRepository
                .findByRecipientIdAndActorIdAndTypeAndPostIdAndReadAtIsNull(recipientId, actorId, type, postId)
                .ifPresentOrElse(
                        existing -> {
                            existing.refreshInteraction(now);
                            notificationRepository.save(existing);
                        },
                        () -> notificationRepository.save(
                                Notification.create(recipientId, actorId, type, postId, commentId, now)));
    }

    /**
     * 撤销互动产生的<b>未读</b>通知：按 actor + type + post（评论类加 comment）定位删除；
     * 已读通知永不撤销。评论类撤销以被删评论自身的作者为 actor（管理员代删同样成立）。
     */
    @Transactional
    public void onInteractionRemoved(UUID actorId, NotificationType type, UUID postId, UUID commentId) {
        if (commentId != null) {
            notificationRepository.deleteUnreadCommentScoped(actorId, type, postId, commentId);
        } else {
            notificationRepository.deleteUnreadPostScoped(actorId, type, postId);
        }
    }

    // ---------- 通知 API ----------

    /**
     * 当前用户通知列表：{@code last_interacted_at} 倒序游标分页。
     * cursor 缺省时取首页；{@code unread_only=true} 仅返回未读；size 缺省 20、上限 50。
     */
    @Transactional(readOnly = true)
    public NotificationListResponse listNotifications(UUID recipientId, String cursor,
                                                      Integer size, Boolean unreadOnly) {
        int safeSize = clampSize(size);
        boolean unread = Boolean.TRUE.equals(unreadOnly);
        List<Notification> rows;
        if (cursor != null) {
            Cursor c = decodeCursor(cursor);
            rows = notificationRepository.findPage(recipientId, unread, safeSize + 1, c.ts(), c.id(), true);
        } else {
            rows = notificationRepository.findPage(recipientId, unread, safeSize + 1, null, null, false);
        }
        boolean hasMore = rows.size() > safeSize;
        List<Notification> page = hasMore ? rows.subList(0, safeSize) : rows;
        String nextCursor = null;
        if (hasMore) {
            Notification last = page.get(page.size() - 1);
            nextCursor = encodeCursor(last.getLastInteractedAt(), last.getId());
        }
        return NotificationListResponse.of(toResponses(page), nextCursor, hasMore);
    }

    /** 当前用户未读通知总数（badge 数据源，count 走索引）。 */
    @Transactional(readOnly = true)
    public long unreadCount(UUID recipientId) {
        return notificationRepository.countByRecipientIdAndReadAtIsNull(recipientId);
    }

    /**
     * 标记单条已读（幂等）。非本人通知与不存在的通知一律 404（同型错误，防枚举）；
     * 已读再调用返回当前状态且无任何写入副作用。
     */
    @Transactional
    public NotificationResponse markRead(UUID id, UUID requesterId, Instant now) {
        Notification notification = notificationRepository.findById(id)
                .filter(n -> n.getRecipientId().equals(requesterId))
                .orElseThrow(NotificationException::notificationNotFound);
        if (notification.markRead(now)) {
            notificationRepository.save(notification);
        }
        return toResponse(notification);
    }

    /** 全部标记已读：批量写当前用户未读行的 read_at；此后新互动仍正常产生未读。 */
    @Transactional
    public void markAllRead(UUID recipientId, Instant now) {
        notificationRepository.markAllRead(recipientId, now);
    }

    // ---------- 内部辅助 ----------

    private int clampSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private List<NotificationResponse> toResponses(List<Notification> notifications) {
        if (notifications.isEmpty()) {
            return List.of();
        }
        List<UUID> actorIds = notifications.stream().map(Notification::getActorId).distinct().toList();
        List<UUID> postIds = notifications.stream().map(Notification::getPostId).distinct().toList();

        Map<UUID, User> actorsById = userRepository.findAllById(actorIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        Map<UUID, Post> postsById = postRepository.findAllById(postIds).stream()
                .collect(Collectors.toMap(Post::getId, p -> p, (a, b) -> a));

        return notifications.stream()
                .map(n -> toResponse(n, actorsById, postsById))
                .toList();
    }

    private NotificationResponse toResponse(Notification notification) {
        return toResponses(List.of(notification)).get(0);
    }

    private NotificationResponse toResponse(Notification notification,
                                            Map<UUID, User> actorsById, Map<UUID, Post> postsById) {
        User actor = actorsById.get(notification.getActorId());
        NotificationResponse.Actor actorView = (actor == null || actor.getStatus() == UserStatus.DELETED)
                ? new NotificationResponse.Actor(notification.getActorId(), null, null)
                : new NotificationResponse.Actor(notification.getActorId(),
                        actor.getDisplayName(), actor.getAvatarUrl());
        Post post = postsById.get(notification.getPostId());
        NotificationResponse.PostRef postView = new NotificationResponse.PostRef(notification.getPostId(),
                post == null ? null : post.getTitle());
        return new NotificationResponse(notification.getId(), notification.getType().name(), actorView,
                postView, notification.getCommentId(), notification.getReadAt(), notification.getCreatedAt());
    }

    private String encodeCursor(Instant ts, UUID id) {
        return B64.encodeToString((ts.toString() + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    /** 游标：编码为 base64(lastInteractedAtISO + "|" + id)，按 (last_interacted_at, id) 截断，无服务端状态。 */
    private Cursor decodeCursor(String token) {
        try {
            String s = new String(B64D.decode(token), StandardCharsets.UTF_8);
            int idx = s.indexOf('|');
            Instant ts = Instant.parse(s.substring(0, idx));
            UUID id = UUID.fromString(s.substring(idx + 1));
            return new Cursor(ts, id);
        } catch (RuntimeException ex) {
            throw NotificationException.invalidCursor();
        }
    }

    /** 游标对（时间戳 + id），与 {@code PostService.Cursor} 同构。 */
    private record Cursor(Instant ts, UUID id) {
    }
}
