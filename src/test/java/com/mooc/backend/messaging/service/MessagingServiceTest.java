package com.mooc.backend.messaging.service;

import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.messaging.domain.Conversation;
import com.mooc.backend.messaging.domain.ConversationRepository;
import com.mooc.backend.messaging.domain.Message;
import com.mooc.backend.messaging.domain.MessageRepository;
import com.mooc.backend.messaging.domain.UserLockRepository;
import com.mooc.backend.messaging.dto.ConversationListResponse;
import com.mooc.backend.messaging.dto.ConversationResponse;
import com.mooc.backend.messaging.dto.MessageListResponse;
import com.mooc.backend.messaging.dto.MessageResponse;
import com.mooc.backend.messaging.exception.MessagingException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 私信业务逻辑单元测试（Task 2.1，TDD RED）。
 *
 * <p>覆盖：幂等建会话（含并发 DVI 兜底回读）、有序对双向一致、自己 422、
 * 不存在 404、不可达统一错误（不区分锁定/注销）、EMAIL_UNVERIFIED 不特判、
 * 发送内容校验（trim / 2001 字符 / 纯空格）、非成员 404（与不存在同型防枚举）、
 * 发送同事务刷新 last_message_*、对方不可达发送被拒、
 * 列表映射（other_user / 预览 80 字符截断 / 未读数 / 软删降级）、
 * 历史倒查反转 + has_more_earlier + before 游标、自动已读、未读计数。
 *
 * <p>Mockito 纯单元测试：SQL 语义（可见性过滤 / 聚合 / 索引）由
 * {@code MessagingControllerIntegrationTest} 在真实 MySQL 上验证。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessagingServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-09-01T09:00:00Z");

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private UserLockRepository userLockRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private MessagingService messagingService;

    private User alice;   // 发起方 / 请求者
    private User bob;     // 接收方
    private UUID aliceId;
    private UUID bobId;
    private UUID carolId;

    @BeforeEach
    void setUp() {
        alice = activeUser("alice@example.com", "Alice");
        bob = activeUser("bob@example.com", "Bob");
        aliceId = alice.getId();
        bobId = bob.getId();
        carolId = activeUser("carol@example.com", "Carol").getId();
    }

    // ---------- createConversation ----------

    @Test
    void createConversationBuildsOrderedPairWithInitiator() {
        stubLocks(alice, bob);
        when(conversationRepository.findByUserLowIdAndUserHighId(lowOf(aliceId, bobId), highOf(aliceId, bobId)))
                .thenReturn(Optional.empty());

        MessagingService.CreateConversationResult result =
                messagingService.createConversation(aliceId, bobId, NOW);

        assertThat(result.newlyCreated()).isTrue();

        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository).saveAndFlush(captor.capture());
        Conversation saved = captor.getValue();
        // 有序对：无符号字节序 (low, high)；initiator 记录发起方
        assertThat(saved.getUserLowId()).isEqualTo(lowOf(aliceId, bobId));
        assertThat(saved.getUserHighId()).isEqualTo(highOf(aliceId, bobId));
        assertThat(saved.getInitiatorId()).isEqualTo(aliceId);
        assertThat(saved.getLastMessageId()).isNull();
        assertThat(saved.getLastMessageAt()).isNull();
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);

        // 会话表示：other_user 为接收方，空会话预览/时间为 null，未读 0
        ConversationResponse response = result.conversation();
        assertThat(response.getId()).isEqualTo(saved.getId());
        assertThat(response.getOtherUser().id()).isEqualTo(bobId);
        assertThat(response.getOtherUser().displayName()).isEqualTo("Bob");
        assertThat(response.getLastMessagePreview()).isNull();
        assertThat(response.getLastMessageAt()).isNull();
        assertThat(response.getUnreadCount()).isZero();
    }

    @Test
    void createConversationIsIdempotentInBothDirections() {
        stubLocks(alice, bob);
        Conversation existing = Conversation.create(aliceId, bobId, EARLIER);
        existing.applyLastMessage(UUID.randomUUID(), EARLIER, EARLIER); // 非空会话：unread_count 走真实计数
        when(conversationRepository.findByUserLowIdAndUserHighId(
                existing.getUserLowId(), existing.getUserHighId())).thenReturn(Optional.of(existing));
        when(messageRepository.countByConversationIdAndSenderIdNotAndReadAtIsNull(existing.getId(), bobId))
                .thenReturn(5L);

        // 反方向（bob → alice）发起：必须命中同一会话（有序对双向一致）
        MessagingService.CreateConversationResult result =
                messagingService.createConversation(bobId, aliceId, NOW);

        assertThat(result.newlyCreated()).isFalse();
        assertThat(result.conversation().getId()).isEqualTo(existing.getId());
        assertThat(result.conversation().getUnreadCount()).isEqualTo(5L);
        verify(conversationRepository, never()).saveAndFlush(any());
    }

    @Test
    void createConversationRejectsSelfRecipient() {
        assertThatThrownBy(() -> messagingService.createConversation(aliceId, aliceId, NOW))
                .isInstanceOfSatisfying(MessagingException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(MessagingException.MESSAGE_SELF);
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getMessage()).isEqualTo("You cannot message yourself");
                });

        verifyNoInteractions(userLockRepository, conversationRepository, messageRepository);
    }

    @Test
    void createConversationReturns404ForUnknownRecipient() {
        stubLocks(alice, null);

        assertThatThrownBy(() -> messagingService.createConversation(aliceId, bobId, NOW))
                .isInstanceOfSatisfying(MessagingException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(MessagingException.USER_NOT_FOUND);
                    assertThat(ex.getStatus().value()).isEqualTo(404);
                });

        verify(conversationRepository, never()).saveAndFlush(any());
    }

    @Test
    void createConversationRejectsDeletedRecipientWithUnifiedError() {
        User deleted = activeUser("gone@example.com", "Gone");
        deleted.softDelete(NOW);
        stubLocks(alice, deleted);

        assertThatThrownBy(() -> messagingService.createConversation(aliceId, deleted.getId(), NOW))
                .isInstanceOfSatisfying(MessagingException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(MessagingException.USER_UNAVAILABLE);
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getMessage()).isEqualTo("This user is not available for messages");
                });
    }

    @Test
    void createConversationLockedAndDeletedShareIdenticalError() {
        User locked = activeUser("locked@example.com", "Locked");
        locked.recordFailedAttempt(NOW, 1, Duration.ofMinutes(15));
        User deleted = activeUser("gone@example.com", "Gone");
        deleted.softDelete(NOW);
        stubLocks(alice, locked, deleted);

        MessagingException lockedError =
                captureError(() -> messagingService.createConversation(aliceId, locked.getId(), NOW));
        MessagingException deletedError =
                captureError(() -> messagingService.createConversation(aliceId, deleted.getId(), NOW));

        // 不区分锁定与注销：同一错误码、同一文案（防状态探测）
        assertThat(lockedError.getCode()).isEqualTo(deletedError.getCode());
        assertThat(lockedError.getMessage()).isEqualTo(deletedError.getMessage());
        assertThat(lockedError.getStatus()).isEqualTo(deletedError.getStatus());
        assertThat(lockedError.getMessage()).isEqualTo("This user is not available for messages");
    }

    @Test
    void createConversationAllowsEmailUnverifiedRecipient() {
        User unverified = User.register("unverified@example.com", "hash", "Unverified", EARLIER);
        stubLocks(alice, unverified);
        when(conversationRepository.findByUserLowIdAndUserHighId(lowOf(aliceId, unverified.getId()),
                highOf(aliceId, unverified.getId()))).thenReturn(Optional.empty());

        MessagingService.CreateConversationResult result =
                messagingService.createConversation(aliceId, unverified.getId(), NOW);

        // EMAIL_UNVERIFIED 不特判：可达性仅拒绝 DELETED / LOCKED
        assertThat(result.newlyCreated()).isTrue();
    }

    @Test
    void createConversationFallsBackToRereadOnUniqueConstraintConflict() {
        stubLocks(alice, bob);
        Conversation existing = Conversation.create(bobId, aliceId, EARLIER);
        existing.applyLastMessage(UUID.randomUUID(), EARLIER, EARLIER); // 非空会话：unread_count 走真实计数
        // 预查未命中（并发窗口），插入撞唯一约束，回读命中既有会话
        when(conversationRepository.findByUserLowIdAndUserHighId(
                existing.getUserLowId(), existing.getUserHighId()))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(conversationRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uk_conversations_pair"));
        when(messageRepository.countByConversationIdAndSenderIdNotAndReadAtIsNull(existing.getId(), aliceId))
                .thenReturn(2L);

        MessagingService.CreateConversationResult result =
                messagingService.createConversation(aliceId, bobId, NOW);

        assertThat(result.newlyCreated()).isFalse();
        assertThat(result.conversation().getId()).isEqualTo(existing.getId());
        assertThat(result.conversation().getUnreadCount()).isEqualTo(2L);
    }

    // ---------- sendMessage ----------

    @Test
    void sendMessageTrimsContentAndRefreshesConversationInSameTransaction() {
        Conversation conversation = Conversation.create(aliceId, bobId, EARLIER);
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));
        when(userRepository.findById(bobId)).thenReturn(Optional.of(bob));

        MessageResponse response =
                messagingService.sendMessage(conversation.getId(), aliceId, "  hello world  ", NOW);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(messageCaptor.capture());
        Message saved = messageCaptor.getValue();
        assertThat(saved.getContent()).isEqualTo("hello world"); // trim 后落库
        assertThat(saved.getSenderId()).isEqualTo(aliceId);
        assertThat(saved.getConversationId()).isEqualTo(conversation.getId());
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getReadAt()).isNull(); // 发送者自己的消息恒 null

        // 同事务刷新会话冗余列
        ArgumentCaptor<Conversation> conversationCaptor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository).save(conversationCaptor.capture());
        Conversation refreshed = conversationCaptor.getValue();
        assertThat(refreshed.getLastMessageId()).isEqualTo(saved.getId());
        assertThat(refreshed.getLastMessageAt()).isEqualTo(NOW);

        assertThat(response.getId()).isEqualTo(saved.getId());
        assertThat(response.getContent()).isEqualTo("hello world");
        assertThat(response.getReadAt()).isNull();
    }

    @Test
    void sendMessageAcceptsExactly2000Characters() {
        Conversation conversation = Conversation.create(aliceId, bobId, EARLIER);
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));
        when(userRepository.findById(bobId)).thenReturn(Optional.of(bob));

        messagingService.sendMessage(conversation.getId(), aliceId, "x".repeat(2000), NOW);

        verify(messageRepository).save(any());
    }

    @Test
    void sendMessageRejects2001Characters() {
        Conversation conversation = Conversation.create(aliceId, bobId, EARLIER);
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));
        when(userRepository.findById(bobId)).thenReturn(Optional.of(bob));

        assertThatThrownBy(() -> messagingService.sendMessage(conversation.getId(), aliceId, "x".repeat(2001), NOW))
                .isInstanceOfSatisfying(MessagingException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(MessagingException.VALIDATION_FAILED);
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                    assertThat(ex.getMessage()).isEqualTo("Message content must not exceed 2000 characters.");
                });

        verify(messageRepository, never()).save(any());
    }

    @Test
    void sendMessageRejectsBlankAndNullContent() {
        Conversation conversation = Conversation.create(aliceId, bobId, EARLIER);
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));
        when(userRepository.findById(bobId)).thenReturn(Optional.of(bob));

        for (String bad : new String[]{"   ", null}) {
            assertThatThrownBy(() -> messagingService.sendMessage(conversation.getId(), aliceId, bad, NOW))
                    .isInstanceOfSatisfying(MessagingException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(MessagingException.VALIDATION_FAILED);
                        assertThat(ex.getMessage()).isEqualTo("Message content must not be empty.");
                    });
        }

        verify(messageRepository, never()).save(any());
    }

    @Test
    void sendMessageRejectsNonMemberWithSameErrorAsMissingConversation() {
        Conversation conversation = Conversation.create(aliceId, bobId, EARLIER);
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));

        MessagingException nonMemberError =
                captureError(() -> messagingService.sendMessage(conversation.getId(), carolId, "hi", NOW));
        assertThat(nonMemberError.getCode()).isEqualTo(MessagingException.CONVERSATION_NOT_FOUND);
        assertThat(nonMemberError.getStatus().value()).isEqualTo(404);
        assertThat(nonMemberError.getMessage()).isEqualTo("Conversation not found");

        // 不存在的会话：同一错误码与文案（防枚举）
        UUID missingId = UUID.randomUUID();
        when(conversationRepository.findById(missingId)).thenReturn(Optional.empty());
        MessagingException missingError =
                captureError(() -> messagingService.sendMessage(missingId, aliceId, "hi", NOW));
        assertThat(missingError.getCode()).isEqualTo(nonMemberError.getCode());
        assertThat(missingError.getMessage()).isEqualTo(nonMemberError.getMessage());

        verify(messageRepository, never()).save(any());
    }

    @Test
    void sendMessageRejectsUnavailableCounterpart() {
        Conversation conversation = Conversation.create(aliceId, bobId, EARLIER);
        bob.softDelete(NOW);
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));
        when(userRepository.findById(bobId)).thenReturn(Optional.of(bob));

        assertThatThrownBy(() -> messagingService.sendMessage(conversation.getId(), aliceId, "hi", NOW))
                .isInstanceOfSatisfying(MessagingException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(MessagingException.USER_UNAVAILABLE);
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                });

        verify(messageRepository, never()).save(any());
    }

    // ---------- listConversations ----------

    @Test
    void listConversationsMapsOtherUserPreviewAndUnreadCount() {
        Conversation withMessages = Conversation.create(aliceId, bobId, EARLIER);
        Message last = Message.create(withMessages.getId(), bobId, "y".repeat(100), EARLIER);
        withMessages.applyLastMessage(last.getId(), EARLIER, EARLIER);
        Conversation empty = Conversation.create(aliceId, carolId, NOW.minusSeconds(30));

        when(conversationRepository.findPage(eq(aliceId), eq(21), isNull(), isNull(), eq(false)))
                .thenReturn(List.of(withMessages, empty));
        when(messageRepository.countUnreadByConversation(aliceId))
                .thenReturn(Map.of(withMessages.getId(), 3L));
        when(userRepository.findAllById(any())).thenReturn(List.of(bob));
        when(messageRepository.findAllById(any())).thenReturn(List.of(last));

        ConversationListResponse response = messagingService.listConversations(aliceId, null, null);

        assertThat(response.isHasMore()).isFalse();
        assertThat(response.getNextCursor()).isNull();
        assertThat(response.getItems()).hasSize(2);

        ConversationResponse first = response.getItems().get(0);
        assertThat(first.getId()).isEqualTo(withMessages.getId());
        assertThat(first.getOtherUser().id()).isEqualTo(bobId);
        assertThat(first.getOtherUser().displayName()).isEqualTo("Bob");
        assertThat(first.getLastMessagePreview()).hasSize(80); // 截断 80 字符
        assertThat(first.getLastMessageAt()).isEqualTo(EARLIER);
        assertThat(first.getUnreadCount()).isEqualTo(3L);

        // 空会话（仅发起者可见的形态）：预览与时间为 null，未读 0
        ConversationResponse second = response.getItems().get(1);
        assertThat(second.getId()).isEqualTo(empty.getId());
        assertThat(second.getLastMessagePreview()).isNull();
        assertThat(second.getLastMessageAt()).isNull();
        assertThat(second.getUnreadCount()).isZero();
    }

    @Test
    void listConversationsDegradesDeletedOtherUserIdentity() {
        bob.softDelete(NOW);
        Conversation conversation = Conversation.create(aliceId, bobId, EARLIER);
        when(conversationRepository.findPage(eq(aliceId), eq(21), isNull(), isNull(), eq(false)))
                .thenReturn(List.of(conversation));
        when(messageRepository.countUnreadByConversation(aliceId)).thenReturn(Map.of());

        ConversationListResponse response = messagingService.listConversations(aliceId, null, null);

        // 条目保留，身份降级（id 保留，display_name / avatar_url 为 null）
        ConversationResponse item = response.getItems().get(0);
        assertThat(item.getOtherUser().id()).isEqualTo(bobId);
        assertThat(item.getOtherUser().displayName()).isNull();
        assertThat(item.getOtherUser().avatarUrl()).isNull();
    }

    @Test
    void listConversationsClampsSizeAndReportsHasMore() {
        List<Conversation> rows = List.of(
                Conversation.create(aliceId, bobId, NOW),
                Conversation.create(aliceId, carolId, NOW.minusSeconds(10)),
                Conversation.create(aliceId, UUID.randomUUID(), NOW.minusSeconds(20)));
        when(conversationRepository.findPage(eq(aliceId), eq(3), isNull(), isNull(), eq(false)))
                .thenReturn(rows);
        when(messageRepository.countUnreadByConversation(aliceId)).thenReturn(Map.of());

        ConversationListResponse response = messagingService.listConversations(aliceId, null, 2);

        assertThat(response.isHasMore()).isTrue();
        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getNextCursor()).isNotBlank();
    }

    @Test
    void listConversationsCursorRoundTripsToRepositoryArguments() {
        Conversation last = Conversation.create(aliceId, bobId, NOW.minusSeconds(20));
        last.applyLastMessage(UUID.randomUUID(), NOW.minusSeconds(5), NOW); // 排序键取 last_message_at
        // 三行（size=2 取满 size+1）→ page=[first, last]，游标取 page 末尾的 last
        when(conversationRepository.findPage(eq(aliceId), eq(3), isNull(), isNull(), eq(false)))
                .thenReturn(List.of(
                        Conversation.create(aliceId, carolId, NOW),
                        last,
                        Conversation.create(aliceId, UUID.randomUUID(), NOW.minusSeconds(30))));
        when(messageRepository.countUnreadByConversation(aliceId)).thenReturn(Map.of());

        ConversationListResponse firstPage = messagingService.listConversations(aliceId, null, 2);
        String cursor = firstPage.getNextCursor();

        // 回传 cursor：解码后必须以 (排序键 ts, id) 精确续拉
        messagingService.listConversations(aliceId, cursor, null);

        verify(conversationRepository).findPage(eq(aliceId), eq(21), eq(NOW.minusSeconds(5)),
                eq(last.getId()), eq(true));
    }

    @Test
    void listConversationsRejectsInvalidCursor() {
        assertThatThrownBy(() -> messagingService.listConversations(aliceId, "not-a-cursor", null))
                .isInstanceOfSatisfying(MessagingException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(MessagingException.VALIDATION_FAILED);
                    assertThat(ex.getMessage()).isEqualTo("Invalid cursor.");
                });
    }

    // ---------- listMessages ----------

    @Test
    void listMessagesReversesToChronologicalOrderAndMarksRead() {
        Conversation conversation = Conversation.create(aliceId, bobId, EARLIER);
        Message oldest = Message.create(conversation.getId(), bobId, "m1", EARLIER);
        Message middle = Message.create(conversation.getId(), bobId, "m2", EARLIER.plusSeconds(1));
        Message newest = Message.create(conversation.getId(), aliceId, "m3", EARLIER.plusSeconds(2));

        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));
        when(messageRepository.findPage(eq(conversation.getId()), eq(4), isNull(), isNull(), eq(false)))
                .thenReturn(List.of(newest, middle, oldest)); // 倒查：新→旧

        MessageListResponse response =
                messagingService.listMessages(conversation.getId(), aliceId, null, 3, NOW);

        // 自动已读：批量置读对方发出的未读消息
        verify(messageRepository).markConversationRead(conversation.getId(), aliceId, NOW);

        assertThat(response.isHasMoreEarlier()).isFalse();
        assertThat(response.getNextBefore()).isNull();
        assertThat(response.getItems()).extracting(MessageResponse::getContent)
                .containsExactly("m1", "m2", "m3"); // 旧→新
    }

    @Test
    void listMessagesReportsHasMoreEarlierAndNextBeforeCursor() {
        Conversation conversation = Conversation.create(aliceId, bobId, EARLIER);
        Message oldest = Message.create(conversation.getId(), bobId, "m1", EARLIER);
        Message middle = Message.create(conversation.getId(), bobId, "m2", EARLIER.plusSeconds(1));
        Message newest = Message.create(conversation.getId(), aliceId, "m3", EARLIER.plusSeconds(2));

        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));
        when(messageRepository.findPage(eq(conversation.getId()), eq(3), isNull(), isNull(), eq(false)))
                .thenReturn(List.of(newest, middle, oldest)); // 取满 size+1 → has_more_earlier

        MessageListResponse response =
                messagingService.listMessages(conversation.getId(), aliceId, null, 2, NOW);

        assertThat(response.isHasMoreEarlier()).isTrue();
        assertThat(response.getNextBefore()).isNotBlank();
        // 首屏为最新 2 条，旧→新
        assertThat(response.getItems()).extracting(MessageResponse::getContent)
                .containsExactly("m2", "m3");

        // next_before 以页内最旧一条的 (created_at, id) 为游标续拉
        messagingService.listMessages(conversation.getId(), aliceId, response.getNextBefore(), 2, NOW);
        verify(messageRepository).findPage(eq(conversation.getId()), eq(3),
                eq(EARLIER.plusSeconds(1)), eq(middle.getId()), eq(true));
    }

    @Test
    void listMessagesRejectsNonMemberWithSameErrorAsMissingConversation() {
        Conversation conversation = Conversation.create(aliceId, bobId, EARLIER);
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));

        MessagingException nonMemberError =
                captureError(() -> messagingService.listMessages(conversation.getId(), carolId, null, null, NOW));
        assertThat(nonMemberError.getCode()).isEqualTo(MessagingException.CONVERSATION_NOT_FOUND);
        assertThat(nonMemberError.getMessage()).isEqualTo("Conversation not found");

        // 不存在的会话：同一错误码与文案（防枚举）
        UUID missingId = UUID.randomUUID();
        when(conversationRepository.findById(missingId)).thenReturn(Optional.empty());
        MessagingException missingError =
                captureError(() -> messagingService.listMessages(missingId, aliceId, null, null, NOW));
        assertThat(missingError.getCode()).isEqualTo(nonMemberError.getCode());
        assertThat(missingError.getMessage()).isEqualTo(nonMemberError.getMessage());
    }

    @Test
    void listMessagesRejectsInvalidBeforeCursorWithoutSideEffects() {
        Conversation conversation = Conversation.create(aliceId, bobId, EARLIER);
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));

        assertThatThrownBy(() ->
                messagingService.listMessages(conversation.getId(), aliceId, "bad-cursor", null, NOW))
                .isInstanceOfSatisfying(MessagingException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(MessagingException.VALIDATION_FAILED));

        // 校验先于副作用：非法游标不触发自动已读
        verify(messageRepository, never()).markConversationRead(any(), any(), any());
    }

    // ---------- markConversationRead / getUnreadCount ----------

    @Test
    void markConversationReadDelegatesToBulkUpdate() {
        Conversation conversation = Conversation.create(aliceId, bobId, EARLIER);
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));

        messagingService.markConversationRead(conversation.getId(), aliceId, NOW);

        verify(messageRepository).markConversationRead(conversation.getId(), aliceId, NOW);
    }

    @Test
    void markConversationReadRejectsNonMember() {
        Conversation conversation = Conversation.create(aliceId, bobId, EARLIER);
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));

        assertThatThrownBy(() ->
                messagingService.markConversationRead(conversation.getId(), carolId, NOW))
                .isInstanceOfSatisfying(MessagingException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo(MessagingException.CONVERSATION_NOT_FOUND));

        verify(messageRepository, never()).markConversationRead(any(), any(), any());
    }

    @Test
    void getUnreadCountDelegatesToAggregatedQuery() {
        when(messageRepository.countUnreadTotal(aliceId)).thenReturn(7L);

        assertThat(messagingService.getUnreadCount(aliceId)).isEqualTo(7L);
    }

    // ---------- helpers ----------

    /** 构造 ACTIVE 状态用户（注册 → 验证码激活），不发 HTTP、不消耗限流配额。 */
    private static User activeUser(String email, String displayName) {
        User user = User.register(email, "hash:" + email, displayName, EARLIER);
        user.issueVerificationCode("code-" + email, EARLIER, Duration.ofHours(1));
        user.consumeVerificationCode("code-" + email, EARLIER.plusSeconds(1));
        return user;
    }

    /**
     * 为 createConversation 的行锁查询打桩：调用方向无关，每个给定用户的 id 查询都返回该用户
     * （可变参数支持一次覆盖 sender + 两个候选接收方；null 元素被忽略）。
     */
    private void stubLocks(User... users) {
        for (User user : users) {
            if (user != null) {
                when(userLockRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
            }
        }
    }

    private static UUID lowOf(UUID a, UUID b) {
        return com.mooc.backend.messaging.domain.UuidOrdering.ordered(a, b).low();
    }

    private static UUID highOf(UUID a, UUID b) {
        return com.mooc.backend.messaging.domain.UuidOrdering.ordered(a, b).high();
    }

    /** 执行并捕获其抛出的 {@link MessagingException}（用于同型错误对比断言）。 */
    private static MessagingException captureError(Runnable action) {
        try {
            action.run();
        } catch (MessagingException ex) {
            return ex;
        }
        throw new AssertionError("Expected MessagingException but none was thrown");
    }
}
