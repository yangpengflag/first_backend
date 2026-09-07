package com.mooc.backend.service;

import com.mooc.backend.entity.User;
import com.mooc.backend.repository.UserRepository;
import com.mooc.backend.entity.UserStatus;
import com.mooc.backend.entity.Conversation;
import com.mooc.backend.repository.ConversationRepository;
import com.mooc.backend.entity.Message;
import com.mooc.backend.repository.MessageRepository;
import com.mooc.backend.service.UuidOrdering;
import com.mooc.backend.repository.UserLockRepository;
import com.mooc.backend.dto.response.ConversationListResponse;
import com.mooc.backend.dto.response.ConversationResponse;
import com.mooc.backend.dto.response.MessageListResponse;
import com.mooc.backend.dto.response.MessageResponse;
import com.mooc.backend.dto.SendMessageRequest;
import com.mooc.backend.exception.MessagingException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 私信业务逻辑（Task 2.2）。
 *
 * <p><b>发起会话（幂等）</b>：先按全局一致的字节序锁两个用户行（低→高，资源层级序无死锁）
 * 串行化并发同对创建——后到线程阻塞至先到线程提交，其后的预查（一致性读）必然命中既有行；
 * 唯一约束 {@code uk_conversations_pair} 与 DVI 回读仅作极端调度下的兜底。
 * 可达性判定对 DELETED / LOCKED 统一抛 {@code USER_UNAVAILABLE}（同一错误码与文案，
 * 不区分锁定与注销，防状态探测）；EMAIL_UNVERIFIED 不特判（spec：该状态已被
 * {@code UserStatusFilter} 全站 403，"可达性"无实质影响）。
 *
 * <p><b>发送消息</b>：内容 trim 后非空、≤2000 字符（业务校验，非成员与不存在会话同型 404
 * 防枚举），落库与 {@code last_message_*} 刷新同事务；对方 DELETED / LOCKED 后发送被拒
 * （spec：进入会话后发送被拒，历史仍可读）。
 *
 * <p><b>会话列表</b>：按 {@code COALESCE(last_message_at, created_at)} 倒序游标分页
 * （空会话以创建时间参与全序，保证游标语义良定义）；可见性延迟（无消息会话仅发起者可见）
 * 内建于仓储查询；{@code unread_count} 走单条聚合 SQL（按会话分组统计对方发出且未读数，
 * 避免 N+1）；对方软删降级照常返回条目。
 *
 * <p><b>消息历史</b>：倒查反转——{@code ORDER BY created_at DESC LIMIT n} 取最新 N 条后
 * 内存反转为旧→新；{@code has_more_earlier} 以取满判断；向上翻页以 {@code before}
 * （页内最旧一条的 created_at 游标）续拉；打开会话（读历史）同事务批量置读
 * 对方发出的未读消息（幂等）。
 */
@Service
public class MessagingService {

    /** 会话列表与消息历史的默认页大小。 */
    static final int DEFAULT_PAGE_SIZE = 20;

    /** 页大小上限。 */
    static final int MAX_PAGE_SIZE = 50;

    /** 会话列表最后一条消息预览的最大长度。 */
    public static final int PREVIEW_MAX_LENGTH = 80;

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UserLockRepository userLockRepository;
    private final UserRepository userRepository;

    public MessagingService(ConversationRepository conversationRepository,
                            MessageRepository messageRepository,
                            UserLockRepository userLockRepository,
                            UserRepository userRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.userLockRepository = userLockRepository;
        this.userRepository = userRepository;
    }

    // ---------- 发起会话 ----------

    /**
     * 发起（或幂等复用）与目标用户的一对一会话。
     * 返回 {@code newlyCreated} 供 HTTP 层区分 201（新建）与 200（命中既有会话）。
     */
    @Transactional
    public CreateConversationResult createConversation(UUID userId, UUID recipientId, Instant now) {
        if (recipientId.equals(userId)) {
            throw MessagingException.messageSelf();
        }
        UuidOrdering.OrderedPair pair = UuidOrdering.ordered(userId, recipientId);
        // 行锁串行化并发同对创建：按字节序低→高加锁（所有线程同序，等待图无环）
        User lowUser = userLockRepository.findByIdForUpdate(pair.low())
                .orElseThrow(MessagingException::userNotFound);
        User highUser = userLockRepository.findByIdForUpdate(pair.high())
                .orElseThrow(MessagingException::userNotFound);
        User recipient = pair.low().equals(recipientId) ? lowUser : highUser;
        if (recipient.getStatus() == UserStatus.DELETED || recipient.getStatus() == UserStatus.LOCKED) {
            throw MessagingException.userUnavailable();
        }

        Optional<Conversation> existing =
                conversationRepository.findByUserLowIdAndUserHighId(pair.low(), pair.high());
        if (existing.isPresent()) {
            return new CreateConversationResult(false,
                    toConversationResponse(existing.get(), userId, recipient));
        }
        try {
            Conversation conversation = Conversation.create(userId, recipientId, now);
            conversationRepository.saveAndFlush(conversation);
            return new CreateConversationResult(true,
                    toConversationResponse(conversation, userId, recipient));
        } catch (DataIntegrityViolationException ex) {
            // 并发兜底（行锁已排除常规路径）：撞唯一约束后回读既有会话
            Conversation reread = conversationRepository.findByUserLowIdAndUserHighId(pair.low(), pair.high())
                    .orElseThrow(() -> ex);
            return new CreateConversationResult(false,
                    toConversationResponse(reread, userId, recipient));
        }
    }

    // ---------- 发送消息 ----------

    /** 会话成员发送一条纯文本消息；落库与 {@code last_message_*} 刷新同事务。 */
    @Transactional
    public MessageResponse sendMessage(UUID conversationId, UUID senderId, String rawContent, Instant now) {
        String content = normalizeContent(rawContent);
        Conversation conversation = conversationRepository.findById(conversationId)
                .filter(c -> c.isMember(senderId))
                .orElseThrow(MessagingException::conversationNotFound);
        UUID recipientId = conversation.otherMemberId(senderId);
        User recipient = userRepository.findById(recipientId).orElse(null);
        if (recipient == null || recipient.getStatus() == UserStatus.DELETED
                || recipient.getStatus() == UserStatus.LOCKED) {
            // spec：对方注销/锁定后历史仍可读，但发送被拒（统一文案，不泄露具体状态）
            throw MessagingException.userUnavailable();
        }
        Message message = Message.create(conversationId, senderId, content, now);
        messageRepository.save(message);
        conversation.applyLastMessage(message.getId(), now, now);
        conversationRepository.save(conversation);
        return toResponse(message, senderId);
    }

    // ---------- 会话列表 ----------

    /**
     * 当前用户会话列表：最近消息时间倒序游标分页（信封 items / next_cursor / has_more）。
     * {@code unread_count} 由单条聚合 SQL 一次取回；对方软删（或行缺失）时条目保留、身份降级。
     */
    @Transactional(readOnly = true)
    public ConversationListResponse listConversations(UUID userId, String cursor, Integer size) {
        int safeSize = clampSize(size);
        List<Conversation> rows;
        if (cursor == null) {
            rows = conversationRepository.findPage(userId, safeSize + 1, null, null, false);
        } else {
            Cursor c = decodeCursor(cursor);
            rows = conversationRepository.findPage(userId, safeSize + 1, c.ts(), c.id(), true);
        }
        boolean hasMore = rows.size() > safeSize;
        List<Conversation> page = hasMore ? rows.subList(0, safeSize) : rows;
        String nextCursor = null;
        if (hasMore) {
            Conversation last = page.get(page.size() - 1);
            nextCursor = encodeCursor(effectiveSortKey(last), last.getId());
        }

        Map<UUID, Long> unreadByConversation = messageRepository.countUnreadByConversation(userId);
        Map<UUID, User> othersById = findUsersById(page.stream()
                .map(c -> c.otherMemberId(userId))
                .distinct()
                .toList());
        Map<UUID, String> previewsById = findPreviews(page);

        List<ConversationResponse> items = page.stream()
                .map(c -> {
                    UUID otherId = c.otherMemberId(userId);
                    return new ConversationResponse(
                            c.getId(),
                            toOtherUser(otherId, othersById.get(otherId)),
                            c.getLastMessageId() == null ? null : truncate(previewsById.get(c.getLastMessageId())),
                            c.getLastMessageAt(),
                            unreadByConversation.getOrDefault(c.getId(), 0L));
                })
                .toList();
        return ConversationListResponse.of(items, nextCursor, hasMore);
    }

    // ---------- 消息历史与已读 ----------

    /**
     * 会话消息历史：倒查反转呈现旧→新，{@code has_more_earlier} 以取满判断，
     * {@code next_before} 供向上翻页续拉。打开会话即已读（同事务批量置读对方未读消息，
     * 幂等，badge 相应减少）。
     */
    @Transactional
    public MessageListResponse listMessages(UUID conversationId, UUID userId, String before,
                                            Integer size, Instant now) {
        int safeSize = clampSize(size);
        conversationRepository.findById(conversationId)
                .filter(c -> c.isMember(userId))
                .orElseThrow(MessagingException::conversationNotFound);
        Cursor beforeCursor = before == null ? null : decodeCursor(before); // 校验先于副作用：非法游标不触发已读
        // 打开会话即已读：先置读（幂等）再取页，使响应中的 read_at 反映本次已读
        messageRepository.markConversationRead(conversationId, userId, now);
        List<Message> rows = beforeCursor == null
                ? messageRepository.findPage(conversationId, safeSize + 1, null, null, false)
                : messageRepository.findPage(conversationId, safeSize + 1, beforeCursor.ts(), beforeCursor.id(), true);

        boolean hasMoreEarlier = rows.size() > safeSize;
        List<Message> page = new ArrayList<>(hasMoreEarlier ? rows.subList(0, safeSize) : rows);
        Collections.reverse(page); // 倒查反转：旧→新呈现
        String nextBefore = null;
        if (hasMoreEarlier && !page.isEmpty()) {
            Message earliest = page.get(0);
            nextBefore = encodeCursor(earliest.getCreatedAt(), earliest.getId());
        }
        List<MessageResponse> items = page.stream()
                .map(m -> toResponse(m, userId))
                .toList();
        return MessageListResponse.of(items, nextBefore, hasMoreEarlier);
    }

    /** 批量置读会话内「对方发出且未读」的消息（幂等）；非成员与不存在会话同型 404。 */
    @Transactional
    public void markConversationRead(UUID conversationId, UUID userId, Instant now) {
        conversationRepository.findById(conversationId)
                .filter(c -> c.isMember(userId))
                .orElseThrow(MessagingException::conversationNotFound);
        messageRepository.markConversationRead(conversationId, userId, now);
    }

    /** 当前用户全部会话中「他人发出且未读」的消息总数（badge 数据源，走索引）。 */
    @Transactional(readOnly = true)
    public long getUnreadCount(UUID userId) {
        return messageRepository.countUnreadTotal(userId);
    }

    // ---------- 内部辅助 ----------

    /** 发起会话的响应组装：预览与未读数取真实值（新建会话为空形态）。 */
    private ConversationResponse toConversationResponse(Conversation conversation, UUID requesterId,
                                                        User recipient) {
        long unreadCount = conversation.getLastMessageId() == null
                ? 0L
                : messageRepository.countByConversationIdAndSenderIdNotAndReadAtIsNull(
                        conversation.getId(), requesterId);
        String preview = null;
        if (conversation.getLastMessageId() != null) {
            preview = truncate(messageRepository.findById(conversation.getLastMessageId())
                    .map(Message::getContent).orElse(null));
        }
        return new ConversationResponse(conversation.getId(), toOtherUser(recipient.getId(), recipient),
                preview, conversation.getLastMessageAt(), unreadCount);
    }

    /** 对方身份视图：注销（软删）或行缺失时降级为 null 身份，条目本身照常返回。 */
    private static ConversationResponse.OtherUser toOtherUser(UUID otherId, User other) {
        if (other == null || other.getStatus() == UserStatus.DELETED) {
            return new ConversationResponse.OtherUser(otherId, null, null);
        }
        return new ConversationResponse.OtherUser(otherId, other.getDisplayName(), other.getAvatarUrl());
    }

    /** 内容规范化：trim 后非空、≤2000 字符（spec：发送消息）。 */
    private static String normalizeContent(String raw) {
        if (raw == null) {
            throw MessagingException.contentEmpty();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw MessagingException.contentEmpty();
        }
        if (trimmed.length() > SendMessageRequest.MAX_CONTENT_LENGTH) {
            throw MessagingException.contentTooLong();
        }
        return trimmed;
    }

    private Map<UUID, User> findUsersById(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
    }

    /** 批量取一页会话的最后一条消息内容（单次 IN 查询，无 N+1）。 */
    private Map<UUID, String> findPreviews(List<Conversation> page) {
        List<UUID> lastMessageIds = page.stream()
                .map(Conversation::getLastMessageId)
                .filter(Objects::nonNull)
                .toList();
        if (lastMessageIds.isEmpty()) {
            return Map.of();
        }
        return messageRepository.findAllById(lastMessageIds).stream()
                .collect(Collectors.toMap(Message::getId, Message::getContent, (a, b) -> a));
    }

    /** 会话排序键：最近消息时间（空会话回退创建时间，与仓储排序表达式一致）。 */
    private static Instant effectiveSortKey(Conversation conversation) {
        return conversation.getLastMessageAt() != null
                ? conversation.getLastMessageAt()
                : conversation.getCreatedAt();
    }

    private static String truncate(String content) {
        if (content == null) {
            return null;
        }
        return content.length() > PREVIEW_MAX_LENGTH ? content.substring(0, PREVIEW_MAX_LENGTH) : content;
    }

    private static int clampSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * 消息出网视图。{@code read_at} 按<b>观察者</b>过滤：自己发出的消息恒输出 null——
     * {@code read_at} 一旦对发送者可见即构成 "Seen" 已读回执，违背 MVP 无回执 UI 的 spec
     * （DB 中的 read_at 仍如实落库，仅用于未读统计与接收方视角）。
     */
    private MessageResponse toResponse(Message message, UUID viewerId) {
        boolean ownMessage = message.getSenderId().equals(viewerId);
        return new MessageResponse(message.getId(), message.getConversationId(), message.getSenderId(),
                message.getContent(), ownMessage ? null : message.getReadAt(), message.getCreatedAt());
    }

    private static String encodeCursor(Instant ts, UUID id) {
        return B64.encodeToString((ts.toString() + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    /** 游标：编码为 base64url(ISO 时间戳 + "|" + id)，按 (ts, id) 截断，无服务端状态。 */
    private static Cursor decodeCursor(String token) {
        try {
            String s = new String(B64D.decode(token), StandardCharsets.UTF_8);
            int idx = s.indexOf('|');
            Instant ts = Instant.parse(s.substring(0, idx));
            UUID id = UUID.fromString(s.substring(idx + 1));
            return new Cursor(ts, id);
        } catch (RuntimeException ex) {
            throw MessagingException.invalidCursor();
        }
    }

    /** 发起会话结果：newlyCreated 区分 201（新建）与 200（幂等命中既有会话）。 */
    public record CreateConversationResult(boolean newlyCreated, ConversationResponse conversation) {
    }

    /** 游标对（时间戳 + id），与 {@code NotificationService.Cursor} 同构。 */
    private record Cursor(Instant ts, UUID id) {
    }
}
