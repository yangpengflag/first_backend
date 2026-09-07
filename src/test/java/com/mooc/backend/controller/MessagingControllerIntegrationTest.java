package com.mooc.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mooc.backend.dto.RegisterRequest;
import com.mooc.backend.entity.User;
import com.mooc.backend.repository.UserRepository;
import com.mooc.backend.service.RateLimiter;
import com.mooc.backend.service.AuthService;
import com.mooc.backend.service.TokenService;
import com.mooc.backend.entity.Conversation;
import com.mooc.backend.repository.ConversationRepository;
import com.mooc.backend.entity.Message;
import com.mooc.backend.repository.MessageRepository;
import com.mooc.backend.dto.response.ConversationResponse;
import com.mooc.backend.dto.response.MessageResponse;
import com.mooc.backend.service.MessagingService;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 私信 HTTP 层集成测试（Task 3.1 / 3.2）。
 *
 * <p>经完整 Spring Security 过滤链（RateLimit → JwtAuth → UserStatus → Controller）
 * 走真实 HTTP：五端点全流程、字段白名单、会话可见性延迟、历史倒查反转 + before 翻页、
 * 自动已读（badge 减少）、未读跨会话聚合、越权 404（防枚举）、401/403/423 门禁、
 * 429 限流格式、预览 80 字符截断、对方软删降级、非法 cursor 400。
 *
 * <p>用户激活沿用 {@code ProfileControllerIntegrationTest} 的 service 直调模式
 * （HTTP 注册会消耗不随事务回滚的限流配额）；令牌由真实 TokenService 签发。
 * 消息种子直接构造实体落库（created_at 显式可控，保证分页全序确定性）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MessagingControllerIntegrationTest {

    private static final String PASS = "Str0ng!Pass";
    private static final Instant BASE = Instant.parse("2026-09-01T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private MessagingService messagingService;

    @Autowired
    private RateLimiter rateLimiter;

    private String bearerA;
    private String bearerB;
    private String bearerC;
    private UUID userAId;
    private UUID userBId;
    private UUID userCId;

    @BeforeEach
    void setUp() {
        bearerA = activatedUser("msg-a@example.com", "MsgA");
        bearerB = activatedUser("msg-b@example.com", "MsgB");
        bearerC = activatedUser("msg-c@example.com", "MsgC");
        userAId = userRepository.findByEmail("msg-a@example.com").orElseThrow().getId();
        userBId = userRepository.findByEmail("msg-b@example.com").orElseThrow().getId();
        userCId = userRepository.findByEmail("msg-c@example.com").orElseThrow().getId();
        // 与 notifications 测试清表模式一致：保证列表聚合只看到本测试构造的数据
        entityManager.createNativeQuery("DELETE FROM messages").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM conversations").executeUpdate();
        // 内存限流器不随事务回滚，防御同上下文其他测试残留
        rateLimiter.reset();
    }

    // ---------- 401 门禁（全端点） ----------

    @Test
    void allMessagingEndpointsRequireAuthentication() throws Exception {
        UUID randomId = UUID.randomUUID();

        mockMvc.perform(get("/api/conversations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mockMvc.perform(post("/api/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientId\":\"" + randomId + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/conversations/" + randomId + "/messages"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mockMvc.perform(post("/api/conversations/" + randomId + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hi\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/messages/unread-count"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    // ---------- 状态门禁（UserStatusFilter 兜底） ----------

    @Test
    void unverifiedEmailUserGets403() throws Exception {
        String bearer = registerOnly("msg-unverified@example.com");

        mockMvc.perform(get("/api/conversations").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("EMAIL_NOT_VERIFIED"));
    }

    @Test
    void lockedUserGets423() throws Exception {
        String bearer = registerOnly("msg-locked@example.com");
        User locked = userRepository.findByEmail("msg-locked@example.com").orElseThrow();
        locked.consumeVerificationCode(locked.getVerificationCode(), Instant.now());
        locked.recordFailedAttempt(Instant.now(), 1, Duration.ofMinutes(15));
        entityManager.flush(); // HTTP 请求在同一测试事务内，需先 flush 才能看到脏状态

        mockMvc.perform(get("/api/conversations").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_LOCKED"));
    }

    // ---------- POST /api/conversations ----------

    @Test
    void createConversationReturns201WithWhitelistedFields() throws Exception {
        String json = mockMvc.perform(post("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, bearerA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientId\":\"" + userBId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.other_user.id").value(userBId.toString()))
                .andExpect(jsonPath("$.other_user.display_name").value("MsgB"))
                .andExpect(jsonPath("$.unread_count").value(0))
                .andReturn().getResponse().getContentAsString();

        // 字段集合严格等于白名单（含 request_id）；「未设置」输出显式 null 而非字段缺失
        JsonNode node = objectMapper.readTree(json);
        assertThat(fieldNames(node)).containsExactlyInAnyOrderElementsOf(ConversationResponse.WHITELISTED_FIELDS);
        assertThat(node.get("last_message_preview").isNull()).isTrue();
        assertThat(node.get("last_message_at").isNull()).isTrue();
    }

    @Test
    void createConversationIsIdempotentInBothDirections() throws Exception {
        String first = mockMvc.perform(post("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, bearerA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientId\":\"" + userBId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String conversationId = objectMapper.readTree(first).get("id").asText();

        // 反方向（B → A）发起：同一会话，200
        mockMvc.perform(post("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, bearerB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientId\":\"" + userAId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(conversationId));

        // 同方向重复发起：同一会话，200，不产生新行
        mockMvc.perform(post("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, bearerA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientId\":\"" + userBId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(conversationId));

        var pair = com.mooc.backend.service.UuidOrdering.ordered(userAId, userBId);
        assertThat(conversationRepository.countByUserLowIdAndUserHighId(pair.low(), pair.high())).isEqualTo(1);
    }

    @Test
    void createConversationRejectsSelfRecipientWith422() throws Exception {
        mockMvc.perform(post("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, bearerA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientId\":\"" + userAId + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("MESSAGE_SELF"))
                .andExpect(jsonPath("$.error.message").value("You cannot message yourself"));
    }

    @Test
    void createConversationReturns404ForUnknownRecipient() throws Exception {
        mockMvc.perform(post("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, bearerA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
    }

    @Test
    void createConversationDeletedAndLockedRecipientsShareUnifiedError() throws Exception {
        User deleted = activatedUserEntity("msg-deleted@example.com", "Deleted");
        deleted.softDelete(Instant.now());
        entityManager.flush();

        User locked = activatedUserEntity("msg-locked-r@example.com", "LockedR");
        locked.recordFailedAttempt(Instant.now(), 1, Duration.ofMinutes(15));
        entityManager.flush();

        for (User target : List.of(deleted, locked)) {
            mockMvc.perform(post("/api/conversations")
                            .header(HttpHeaders.AUTHORIZATION, bearerA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"recipientId\":\"" + target.getId() + "\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("USER_UNAVAILABLE"))
                    .andExpect(jsonPath("$.error.message").value("This user is not available for messages"));
        }
    }

    @Test
    void createConversationAllowsEmailUnverifiedRecipient() throws Exception {
        String bearer = registerOnly("msg-unverified-r@example.com");
        UUID unverifiedId = userRepository.findByEmail("msg-unverified-r@example.com").orElseThrow().getId();

        // EMAIL_UNVERIFIED 不特判（该状态本就被全站 403 挡住，可达性无实质影响）
        mockMvc.perform(post("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, bearerA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientId\":\"" + unverifiedId + "\"}"))
                .andExpect(status().isCreated());
    }

    // ---------- POST /api/conversations/{id}/messages ----------

    @Test
    void sendMessageReturns201TrimsContentAndUpdatesConversation() throws Exception {
        UUID conversationId = createConversationViaApi(bearerA, userBId);

        String json = mockMvc.perform(post("/api/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearerA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"  hello world  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conversation_id").value(conversationId.toString()))
                .andExpect(jsonPath("$.sender_id").value(userAId.toString()))
                .andExpect(jsonPath("$.content").value("hello world"))
                .andReturn().getResponse().getContentAsString();

        // 字段白名单 + 发送者自己的消息 read_at 恒 null（显式 null）
        JsonNode node = objectMapper.readTree(json);
        assertThat(fieldNames(node)).containsExactlyInAnyOrderElementsOf(MessageResponse.WHITELISTED_FIELDS);
        assertThat(node.get("read_at").isNull()).isTrue();

        // 会话列表排序依据更新：B（收件人）现在能看到该会话，预览为 trim 后内容
        mockMvc.perform(get("/api/conversations").header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(conversationId.toString()))
                .andExpect(jsonPath("$.items[0].last_message_preview").value("hello world"));
    }

    @Test
    void sendMessageRejectsBlankContentAndLeavesDbUnchanged() throws Exception {
        UUID conversationId = createConversationViaApi(bearerA, userBId);

        mockMvc.perform(post("/api/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearerA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(messageRepository.count()).isZero();
    }

    @Test
    void sendMessageRejects2001Characters() throws Exception {
        UUID conversationId = createConversationViaApi(bearerA, userBId);

        mockMvc.perform(post("/api/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearerA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + "x".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(messageRepository.count()).isZero();
    }

    @Test
    void sendMessageRejectsNonMemberWithSameErrorAsMissingConversation() throws Exception {
        UUID conversationId = createConversationViaApi(bearerA, userBId);
        UUID missingId = UUID.randomUUID();

        String nonMember = mockMvc.perform(post("/api/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearerC)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hi\"}"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String missing = mockMvc.perform(post("/api/conversations/" + missingId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearerC)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hi\"}"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        // 同型 404 防枚举：code / message 完全一致
        assertThat(objectMapper.readTree(nonMember).get("error").get("code").asText())
                .isEqualTo(objectMapper.readTree(missing).get("error").get("code").asText())
                .isEqualTo("CONVERSATION_NOT_FOUND");
        assertThat(objectMapper.readTree(nonMember).get("error").get("message").asText())
                .isEqualTo("Conversation not found");
    }

    @Test
    void sendMessageToDeletedCounterpartIsRejectedWithUnifiedError() throws Exception {
        UUID conversationId = createConversationViaApi(bearerA, userBId);
        User deletedB = userRepository.findByEmail("msg-b@example.com").orElseThrow();
        deletedB.softDelete(Instant.now());
        entityManager.flush();

        // spec：对方注销后历史仍可读，但发送被拒（统一文案）
        mockMvc.perform(post("/api/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearerA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello?\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("USER_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.message").value("This user is not available for messages"));
    }

    // ---------- GET /api/conversations ----------

    @Test
    void conversationListEnvelopeHasWhitelistedFields() throws Exception {
        String json = mockMvc.perform(get("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.has_more").value(false))
                .andReturn().getResponse().getContentAsString();

        JsonNode node = objectMapper.readTree(json);
        assertThat(fieldNames(node)).containsExactlyInAnyOrder("items", "next_cursor", "has_more", "request_id");
        assertThat(node.get("next_cursor").isNull()).isTrue();
    }

    @Test
    void emptyConversationIsHiddenFromRecipientUntilFirstMessage() throws Exception {
        UUID conversationId = createConversationViaApi(bearerA, userBId);

        // 可见性延迟：发起者可见空会话（以便发出首条消息）
        mockMvc.perform(get("/api/conversations").header(HttpHeaders.AUTHORIZATION, bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(conversationId.toString()));

        // 收件人列表不出现无消息会话
        mockMvc.perform(get("/api/conversations").header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        // 首条消息发出后收件人可见
        messagingService.sendMessage(conversationId, userAId, "first!", BASE);

        mockMvc.perform(get("/api/conversations").header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(conversationId.toString()));
    }

    @Test
    void conversationListSortsByLastMessageTimeAndPaginatesWithCursor() throws Exception {
        UUID userDId = activatedUserEntity("msg-d@example.com", "MsgD").getId();
        Conversation convB = conversationRepository.save(Conversation.create(userAId, userBId, BASE));
        Conversation convC = conversationRepository.save(Conversation.create(userAId, userCId, BASE));
        Conversation convD = conversationRepository.save(Conversation.create(userAId, userDId, BASE));
        seedMessage(convB.getId(), userBId, "from-b", BASE);
        seedMessage(convC.getId(), userCId, "from-c", BASE.plusSeconds(60));
        seedMessage(convD.getId(), userDId, "from-d", BASE.plusSeconds(120));

        String page1 = mockMvc.perform(get("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, bearerA)
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.has_more").value(true))
                .andReturn().getResponse().getContentAsString();
        JsonNode node1 = objectMapper.readTree(page1);
        assertThat(node1.get("items").get(0).get("id").asText()).isEqualTo(convD.getId().toString());
        assertThat(node1.get("items").get(1).get("id").asText()).isEqualTo(convC.getId().toString());
        String cursor = node1.get("next_cursor").asText();

        mockMvc.perform(get("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, bearerA)
                        .param("cursor", cursor)
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.has_more").value(false))
                .andExpect(jsonPath("$.items[0].id").value(convB.getId().toString()));
    }

    @Test
    void conversationListTruncatesPreviewTo80Characters() throws Exception {
        UUID conversationId = createConversationViaApi(bearerA, userBId);
        seedMessage(conversationId, userAId, "z".repeat(100), BASE);

        mockMvc.perform(get("/api/conversations").header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].last_message_preview").value("z".repeat(80)));
    }

    @Test
    void conversationListShowsUnreadCountPerConversation() throws Exception {
        Conversation convB = conversationRepository.save(Conversation.create(userAId, userBId, BASE));
        Conversation convC = conversationRepository.save(Conversation.create(userAId, userCId, BASE));
        seedMessage(convB.getId(), userAId, "from-a-1", BASE);
        seedMessage(convB.getId(), userAId, "from-a-2", BASE.plusSeconds(1));
        seedMessage(convC.getId(), userCId, "from-c", BASE.plusSeconds(2));

        // B：convB 有 2 条未读（A 发出）；convC 与 B 无关，不出现
        mockMvc.perform(get("/api/conversations").header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(convB.getId().toString()))
                .andExpect(jsonPath("$.items[0].unread_count").value(2));

        // A：convC 1 条未读（C 发出）排前，convB 0 条（自己发的）
        mockMvc.perform(get("/api/conversations").header(HttpHeaders.AUTHORIZATION, bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(convC.getId().toString()))
                .andExpect(jsonPath("$.items[0].unread_count").value(1))
                .andExpect(jsonPath("$.items[1].id").value(convB.getId().toString()))
                .andExpect(jsonPath("$.items[1].unread_count").value(0));
    }

    @Test
    void conversationListDegradesDeletedOtherUserIdentityButKeepsEntry() throws Exception {
        UUID conversationId = createConversationViaApi(bearerA, userBId);
        seedMessage(conversationId, userAId, "before deletion", BASE);
        User deletedB = userRepository.findByEmail("msg-b@example.com").orElseThrow();
        deletedB.softDelete(Instant.now());
        entityManager.flush();

        String json = mockMvc.perform(get("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].other_user.id").value(userBId.toString()))
                .andReturn().getResponse().getContentAsString();

        // 身份降级：display_name / avatar_url 显式 null
        JsonNode item = objectMapper.readTree(json).get("items").get(0);
        assertThat(item.get("other_user").get("display_name").isNull()).isTrue();
        assertThat(item.get("other_user").get("avatar_url").isNull()).isTrue();
    }

    @Test
    void conversationListRejectsInvalidCursor() throws Exception {
        mockMvc.perform(get("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, bearerA)
                        .param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ---------- GET /api/conversations/{id}/messages ----------

    @Test
    void messageHistoryReturnsNewestPageInChronologicalOrderWithWhitelistedFields() throws Exception {
        UUID conversationId = createConversationViaApi(bearerA, userBId);
        for (int i = 0; i < 25; i++) {
            seedMessage(conversationId, i % 2 == 0 ? userAId : userBId, "msg-" + String.format("%02d", i),
                    BASE.plusSeconds(i));
        }

        String json = mockMvc.perform(get("/api/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.has_more_earlier").value(true))
                .andReturn().getResponse().getContentAsString();

        JsonNode node = objectMapper.readTree(json);
        assertThat(fieldNames(node)).containsExactlyInAnyOrder("items", "next_before", "has_more_earlier", "request_id");
        assertThat(node.get("items").size()).isEqualTo(20);
        // 首屏 = 最新 20 条（msg-05..24），旧→新
        List<String> contents = new ArrayList<>();
        node.get("items").forEach(m -> contents.add(m.get("content").asText()));
        assertThat(contents.get(0)).isEqualTo("msg-05");
        assertThat(contents.get(19)).isEqualTo("msg-24");
        for (int i = 1; i < contents.size(); i++) {
            assertThat(contents.get(i).compareTo(contents.get(i - 1))).isPositive(); // 严格递增
        }
        assertThat(node.get("next_before").isTextual()).isTrue();
    }

    @Test
    void messageHistoryPagesUpwardWithBeforeCursorToOldest() throws Exception {
        UUID conversationId = createConversationViaApi(bearerA, userBId);
        for (int i = 0; i < 45; i++) {
            seedMessage(conversationId, i % 2 == 0 ? userAId : userBId, "m-" + String.format("%02d", i),
                    BASE.plusSeconds(i));
        }

        Set<String> seen = new HashSet<>();
        List<List<String>> pagesByFetchOrder = new ArrayList<>(); // 取页方向：新→旧
        String cursor = null;
        boolean lastPageHasMore = true;
        for (int page = 0; page < 5 && lastPageHasMore; page++) {
            var request = get("/api/conversations/" + conversationId + "/messages")
                    .header(HttpHeaders.AUTHORIZATION, bearerA);
            if (cursor != null) {
                request = request.param("before", cursor);
            }
            JsonNode node = objectMapper.readTree(mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            List<String> contents = new ArrayList<>();
            node.get("items").forEach(m -> {
                String content = m.get("content").asText();
                assertThat(seen.add(content)).as("duplicate content %s", content).isTrue();
                contents.add(content);
            });
            // 每页内部严格旧→新
            for (int i = 1; i < contents.size(); i++) {
                assertThat(contents.get(i).compareTo(contents.get(i - 1))).isPositive();
            }
            pagesByFetchOrder.add(contents);
            lastPageHasMore = node.get("has_more_earlier").asBoolean();
            cursor = node.get("next_before").isNull() ? null : node.get("next_before").asText();
        }

        assertThat(lastPageHasMore).isFalse();
        assertThat(cursor).isNull();
        assertThat(seen).hasSize(45); // 无重无漏
        // 逆页序拼接（旧页在前）后全序列应严格旧→新（m-00..44）
        List<String> chronological = new ArrayList<>();
        for (int i = pagesByFetchOrder.size() - 1; i >= 0; i--) {
            chronological.addAll(pagesByFetchOrder.get(i));
        }
        for (int i = 0; i < 45; i++) {
            assertThat(chronological.get(i)).isEqualTo("m-" + String.format("%02d", i));
        }
    }

    @Test
    void openingConversationAutoMarksCounterpartMessagesRead() throws Exception {
        UUID conversationId = createConversationViaApi(bearerA, userBId);
        seedMessage(conversationId, userAId, "from-a-1", BASE);
        seedMessage(conversationId, userAId, "from-a-2", BASE.plusSeconds(1));

        // B 未读 badge = 2
        mockMvc.perform(get("/api/messages/unread-count").header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread_count").value(2));

        // B 打开会话：返回中对方消息 read_at 已写入
        String json = mockMvc.perform(get("/api/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        objectMapper.readTree(json).get("items")
                .forEach(m -> assertThat(m.get("read_at").isNull()).isFalse());

        // badge 归零；重复打开幂等
        mockMvc.perform(get("/api/messages/unread-count").header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread_count").value(0));
        mockMvc.perform(get("/api/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/messages/unread-count").header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread_count").value(0));

        // A 自己的消息 read_at 恒 null（已读语义只作用于「对方发出」的消息）
        String asA = mockMvc.perform(get("/api/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearerA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        objectMapper.readTree(asA).get("items")
                .forEach(m -> assertThat(m.get("read_at").isNull()).isTrue());
    }

    @Test
    void messageHistoryRejectsNonMemberWith404() throws Exception {
        UUID conversationId = createConversationViaApi(bearerA, userBId);
        seedMessage(conversationId, userAId, "secret", BASE);

        mockMvc.perform(get("/api/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearerC))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CONVERSATION_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Conversation not found"));
    }

    @Test
    void messageHistoryRejectsInvalidBeforeCursor() throws Exception {
        UUID conversationId = createConversationViaApi(bearerA, userBId);

        mockMvc.perform(get("/api/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearerA)
                        .param("before", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ---------- GET /api/messages/unread-count ----------

    @Test
    void unreadCountAggregatesAcrossConversationsAndWhitelistsFields() throws Exception {
        Conversation convB = conversationRepository.save(Conversation.create(userAId, userBId, BASE));
        Conversation convC = conversationRepository.save(Conversation.create(userCId, userBId, BASE));
        seedMessage(convB.getId(), userAId, "b1", BASE);
        seedMessage(convB.getId(), userAId, "b2", BASE.plusSeconds(1));
        seedMessage(convB.getId(), userAId, "b3", BASE.plusSeconds(2));
        seedMessage(convC.getId(), userCId, "c1", BASE.plusSeconds(3));
        seedMessage(convC.getId(), userCId, "c2", BASE.plusSeconds(4));

        String json = mockMvc.perform(get("/api/messages/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread_count").value(5))
                .andReturn().getResponse().getContentAsString();

        assertThat(fieldNames(objectMapper.readTree(json)))
                .containsExactlyInAnyOrder("unread_count", "request_id");

        // badge 场景：读完一个会话后变为 2
        mockMvc.perform(get("/api/conversations/" + convB.getId() + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/messages/unread-count").header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread_count").value(2));

        // 自己发出的消息不计入
        mockMvc.perform(get("/api/messages/unread-count").header(HttpHeaders.AUTHORIZATION, bearerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread_count").value(0));
    }

    // ---------- 429 限流 ----------

    @Test
    void sendMessageRateLimitsAtTenthMessagePerMinute() throws Exception {
        UUID conversationId = createConversationViaApi(bearerA, userBId);

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/conversations/" + conversationId + "/messages")
                            .header(HttpHeaders.AUTHORIZATION, bearerA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"msg " + i + "\"}"))
                    .andExpect(status().isCreated());
        }
        // 第 11 条：429，对齐全站格式
        mockMvc.perform(post("/api/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearerA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"msg 10\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.error.message").value("Too many requests. Please try again later."));

        // 超限请求不落库：B 视角未读仍为 10
        assertThat(messageRepository.countByConversationIdAndSenderIdNotAndReadAtIsNull(conversationId, userBId))
                .isEqualTo(10);
    }

    // ---------- helpers ----------

    /** 注册 + 验证激活 + 签发令牌（service 直调，不走 HTTP 注册以避免限流配额消耗）。 */
    private String activatedUser(String email, String displayName) {
        activatedUserEntity(email, displayName);
        return "Bearer " + tokenService.generateAccessToken(
                userRepository.findByEmail(email).orElseThrow().getId());
    }

    /** 注册 + 验证激活，返回激活后的实体。 */
    private User activatedUserEntity(String email, String displayName) {
        authService.register(new RegisterRequest(email, PASS, displayName));
        authService.verifyEmail(userRepository.findByEmail(email).orElseThrow().getVerificationCode());
        return userRepository.findByEmail(email).orElseThrow();
    }

    /** 仅注册（EMAIL_UNVERIFIED），用于状态门禁测试。 */
    private String registerOnly(String email) {
        authService.register(new RegisterRequest(email, PASS, "Unverified"));
        User user = userRepository.findByEmail(email).orElseThrow();
        return "Bearer " + tokenService.generateAccessToken(user.getId());
    }

    /** 经 HTTP API 建会话，返回会话 id。 */
    private UUID createConversationViaApi(String bearer, UUID recipientId) throws Exception {
        String json = mockMvc.perform(post("/api/conversations")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientId\":\"" + recipientId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(json).get("id").asText());
    }

    /** 直接构造消息落库，并同步刷新会话 last_message_*（种子数据，绕过限流与 HTTP）。 */
    private void seedMessage(UUID conversationId, UUID senderId, String content, Instant at) {
        Message message = messageRepository.save(Message.create(conversationId, senderId, content, at));
        Conversation conversation = conversationRepository.findById(conversationId).orElseThrow();
        conversation.applyLastMessage(message.getId(), at, at);
        conversationRepository.save(conversation);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
