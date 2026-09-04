package com.mooc.backend.notifications.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mooc.backend.auth.api.RegisterRequest;
import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.auth.service.AuthService;
import com.mooc.backend.auth.service.TokenService;
import com.mooc.backend.notifications.domain.Notification;
import com.mooc.backend.notifications.domain.NotificationRepository;
import com.mooc.backend.notifications.domain.NotificationType;
import com.mooc.backend.notifications.dto.NotificationResponse;
import com.mooc.backend.notifications.service.NotificationService;

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通知 HTTP 层集成测试（Task 3.1）。
 *
 * <p>经完整 Spring Security 过滤链（RateLimit → JwtAuth → UserStatus → Controller）
 * 走真实 HTTP：cursor 分页与 recipient 隔离、unread-count、read 幂等、read-all、
 * 他人通知 404（防枚举）、401/403 门禁、actor 软删降级，以及 votes / comments /
 * bookmarks 写路径到通知生成的端到端挂接（取消互动撤销未读、已读保留）。
 *
 * <p>用户激活沿用 {@code ProfileControllerIntegrationTest} 的 service 直调模式
 * （HTTP 注册会消耗不随事务回滚的限流配额）；令牌由真实 TokenService 签发。
 * 测试数据以 {@code NotificationService.onInteraction} 直接种入（同事务语义与生产一致）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NotificationsControllerIntegrationTest {

    private static final String PASS = "Str0ng!Pass";
    private static final Instant BASE = Instant.parse("2026-08-28T10:00:00Z");

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
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notificationRepository;

    private String bearerB; // recipient（帖主 / 通知接收者）
    private String bearerC; // actor（互动者 / 通知产生者）
    private UUID userBId;
    private UUID userCId;

    @BeforeEach
    void setUp() {
        bearerB = activatedUser("notif-b@example.com", "NotifB");
        bearerC = activatedUser("notif-c@example.com", "NotifC");
        userBId = userRepository.findByEmail("notif-b@example.com").orElseThrow().getId();
        userCId = userRepository.findByEmail("notif-c@example.com").orElseThrow().getId();
        // 与 PostRepositoryTest 清表模式一致：保证列表聚合只看到本测试构造的数据
        entityManager.createNativeQuery("DELETE FROM notifications").executeUpdate();
    }

    // ---------- 401 门禁 ----------

    @Test
    void listRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void unreadCountRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/notifications/unread-count"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void markReadRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/notifications/" + UUID.randomUUID() + "/read"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void markAllReadRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/notifications/read-all"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 状态门禁（UserStatusFilter 兜底，少量断言） ----------

    @Test
    void unverifiedEmailUserGets403() throws Exception {
        authService.register(new RegisterRequest("notif-unverified@example.com", PASS, "Unverified"));
        User unverified = userRepository.findByEmail("notif-unverified@example.com").orElseThrow();
        String bearer = "Bearer " + tokenService.generateAccessToken(unverified.getId());

        mockMvc.perform(get("/api/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("EMAIL_NOT_VERIFIED"));
    }

    // ---------- 列表：字段白名单 / 分页 / 隔离 / unread_only / cursor ----------

    @Test
    void listReturnsWhitelistedFieldsOnly() throws Exception {
        UUID postId = createPost(bearerB);
        notificationService.onInteraction(userCId, userBId, NotificationType.POST_LIKED, postId, null, BASE);

        String json = mockMvc.perform(get("/api/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode envelope = objectMapper.readTree(json);
        Set<String> envelopeFields = new HashSet<>();
        envelope.fieldNames().forEachRemaining(envelopeFields::add);
        assertThat(envelopeFields).containsExactlyInAnyOrder("request_id", "items", "next_cursor", "has_more");

        JsonNode item = envelope.get("items").get(0);
        Set<String> itemFields = new HashSet<>();
        item.fieldNames().forEachRemaining(itemFields::add);
        assertThat(itemFields).containsExactlyInAnyOrderElementsOf(NotificationResponse.WHITELISTED_FIELDS);

        assertThat(item.get("type").asText()).isEqualTo("POST_LIKED");
        assertThat(item.get("actor").get("id").asText()).isEqualTo(userCId.toString());
        assertThat(item.get("actor").get("display_name").asText()).isEqualTo("NotifC");
        assertThat(item.get("post").get("id").asText()).isEqualTo(postId.toString());
        assertThat(item.get("post").get("title").asText()).isEqualTo("Post");
        assertThat(item.get("comment_id").isNull()).isTrue();
        assertThat(item.get("read_at").isNull()).isTrue();
        assertThat(item.has("created_at")).isTrue();
        // actor 不含 email 或凭证字段
        assertThat(item.get("actor").has("email")).isFalse();
    }

    @Test
    void listPaginatesTwentyPerDefaultPageWithoutOverlap() throws Exception {
        for (int i = 0; i < 25; i++) {
            seedForB(NotificationType.POST_LIKED, UUID.randomUUID(), null, BASE.plusSeconds(i));
        }

        String firstJson = mockMvc.perform(get("/api/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.has_more").value(true))
                .andReturn().getResponse().getContentAsString();
        JsonNode first = objectMapper.readTree(firstJson);
        String nextCursor = first.get("next_cursor").asText();
        assertThat(nextCursor).isNotBlank();

        String secondJson = mockMvc.perform(get("/api/notifications")
                        .queryParam("cursor", nextCursor)
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.has_more").value(false))
                .andExpect(jsonPath("$.next_cursor").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        Set<String> ids = new HashSet<>();
        collectIds(objectMapper.readTree(firstJson), ids);
        collectIds(objectMapper.readTree(secondJson), ids);
        assertThat(ids).hasSize(25); // 无重叠无遗漏
    }

    @Test
    void listClampsSizeToMax50() throws Exception {
        for (int i = 0; i < 3; i++) {
            seedForB(NotificationType.POST_LIKED, UUID.randomUUID(), null, BASE.plusSeconds(i));
        }

        mockMvc.perform(get("/api/notifications")
                        .queryParam("size", "500")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3));
    }

    @Test
    void listIsolatesRecipient() throws Exception {
        // 种一条 recipient = C 的通知（B 是 actor）；B 查询应一无所见，C 可见
        UUID postOfB = createPost(bearerB);
        seedForC(NotificationType.POST_LIKED, postOfB, null, BASE);

        mockMvc.perform(get("/api/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.has_more").value(false));

        mockMvc.perform(get("/api/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void listUnreadOnlyFiltersReadEntries() throws Exception {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        String idOfP2 = seedForB(NotificationType.POST_LIKED, p2, null, BASE.plusSeconds(1));
        seedForB(NotificationType.POST_LIKED, p1, null, BASE);
        seedForB(NotificationType.POST_LIKED, p3, null, BASE.plusSeconds(2));

        mockMvc.perform(post("/api/notifications/" + idOfP2 + "/read")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications")
                        .queryParam("unread_only", "true")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));

        mockMvc.perform(get("/api/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3));
    }

    @Test
    void listRejectsMalformedCursorAs400() throws Exception {
        mockMvc.perform(get("/api/notifications")
                        .queryParam("cursor", "garbage-cursor")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    // ---------- 未读计数 ----------

    @Test
    void unreadCountCountsOnlyUnread() throws Exception {
        UUID p1 = UUID.randomUUID();
        String idOfP1 = seedForB(NotificationType.POST_LIKED, p1, null, BASE);
        seedForB(NotificationType.POST_COMMENTED, UUID.randomUUID(), UUID.randomUUID(), BASE.plusSeconds(1));
        seedForB(NotificationType.POST_BOOKMARKED, UUID.randomUUID(), null, BASE.plusSeconds(2));

        mockMvc.perform(post("/api/notifications/" + idOfP1 + "/read")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread_count").value(2));
    }

    // ---------- 标记已读：幂等 / 防枚举 404 ----------

    @Test
    void markReadIsIdempotentAndKeepsFirstReadAt() throws Exception {
        String id = seedForB(NotificationType.POST_LIKED, UUID.randomUUID(), null, BASE);

        String first = mockMvc.perform(post("/api/notifications/" + id + "/read")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read_at").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/notifications/" + id + "/read")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(second).get("read_at").asText())
                .isEqualTo(objectMapper.readTree(first).get("read_at").asText());
    }

    @Test
    void markReadForeignOrMissingNotificationReturns404WithSameShape() throws Exception {
        String foreignId = seedForC(NotificationType.POST_LIKED, UUID.randomUUID(), null, BASE);
        UUID missingId = UUID.randomUUID();

        // 他人通知与不存在通知：同一错误码，不泄露存在性
        for (String id : List.of(foreignId, missingId.toString())) {
            mockMvc.perform(post("/api/notifications/" + id + "/read")
                            .header(HttpHeaders.AUTHORIZATION, bearerB))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("NOTIFICATION_NOT_FOUND"));
        }
    }

    // ---------- 全部标记已读 ----------

    @Test
    void readAllClearsBadgeAndNewInteractionsStillNotify() throws Exception {
        for (int i = 0; i < 3; i++) {
            seedForB(NotificationType.POST_LIKED, UUID.randomUUID(), null, BASE.plusSeconds(i));
        }

        mockMvc.perform(post("/api/notifications/read-all")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread_count").value(0));

        // 此后新互动仍正常产生未读
        seedForB(NotificationType.POST_LIKED, UUID.randomUUID(), null, BASE.plusSeconds(10));
        mockMvc.perform(get("/api/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread_count").value(1));
    }

    // ---------- actor 降级 ----------

    @Test
    void listKeepsEntryAndDegradesSoftDeletedActor() throws Exception {
        UUID postId = createPost(bearerB);
        notificationService.onInteraction(userCId, userBId, NotificationType.POST_LIKED, postId, null, BASE);
        // actor 注销（软删）
        userRepository.findById(userCId).orElseThrow().softDelete(Instant.now());
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get("/api/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].actor.id").value(userCId.toString()))
                .andExpect(jsonPath("$.items[0].actor.display_name").doesNotExist())
                .andExpect(jsonPath("$.items[0].actor.avatar_url").doesNotExist());
    }

    // ---------- 端到端挂接：votes / comments / bookmarks ----------

    @Test
    void upVoteNotifiesAuthorAndCancelRevokesUnread() throws Exception {
        UUID postId = createPost(bearerB);

        mockMvc.perform(post("/api/posts/" + postId + "/vote")
                        .header(HttpHeaders.AUTHORIZATION, bearerC)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vote_type\":\"UP\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].type").value("POST_LIKED"))
                .andExpect(jsonPath("$.items[0].actor.id").value(userCId.toString()))
                .andExpect(jsonPath("$.items[0].post.id").value(postId.toString()));

        mockMvc.perform(get("/api/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(jsonPath("$.unread_count").value(1));

        // 再投 UP → 取消（物理删除）→ 未读通知撤销
        mockMvc.perform(post("/api/posts/" + postId + "/vote")
                        .header(HttpHeaders.AUTHORIZATION, bearerC)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vote_type\":\"UP\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(jsonPath("$.unread_count").value(0));
        mockMvc.perform(get("/api/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void readNotificationSurvivesVoteCancel() throws Exception {
        UUID postId = createPost(bearerB);

        mockMvc.perform(post("/api/posts/" + postId + "/vote")
                        .header(HttpHeaders.AUTHORIZATION, bearerC)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vote_type\":\"UP\"}"))
                .andExpect(status().isOk());

        String json = mockMvc.perform(get("/api/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andReturn().getResponse().getContentAsString();
        String notificationId = objectMapper.readTree(json).get("items").get(0).get("id").asText();

        mockMvc.perform(post("/api/notifications/" + notificationId + "/read")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk());

        // 取消投票：已读通知保留
        mockMvc.perform(post("/api/posts/" + postId + "/vote")
                        .header(HttpHeaders.AUTHORIZATION, bearerC)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vote_type\":\"UP\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].read_at").isNotEmpty());
    }

    @Test
    void commentNotifiesPostAuthorAndReplyNotifiesRepliedUserOnly() throws Exception {
        UUID postId = createPost(bearerB);

        // C 顶层评论 B 的帖子 → B 收 POST_COMMENTED（含 comment_id）
        String commentJson = mockMvc.perform(post("/api/posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, bearerC)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Nice trip!\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String commentId = objectMapper.readTree(commentJson).get("id").asText();

        mockMvc.perform(get("/api/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].type").value("POST_COMMENTED"))
                .andExpect(jsonPath("$.items[0].actor.id").value(userCId.toString()))
                .andExpect(jsonPath("$.items[0].comment_id").value(commentId));

        // B 回复 C 的评论 → 仅 C 收通知（B 是帖主但被回复者是评论者 C，帖主不重复收）
        mockMvc.perform(post("/api/posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, bearerB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Thanks!\",\"parent_comment_id\":\"" + commentId + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, bearerC))
                .andExpect(jsonPath("$.unread_count").value(1));
        mockMvc.perform(get("/api/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(jsonPath("$.unread_count").value(1)); // B 只保留 C 顶层评论那一条

        // C 软删其顶层评论 → 撤销其产生的未读通知（B 的）；且级联软删 B 的回复，
        // B 回复产生的通知（C 的）随级联删除一并撤销
        mockMvc.perform(delete("/api/comments/" + commentId)
                        .header(HttpHeaders.AUTHORIZATION, bearerC))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(jsonPath("$.unread_count").value(0));
        mockMvc.perform(get("/api/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, bearerC))
                .andExpect(jsonPath("$.unread_count").value(0));
    }

    @Test
    void bookmarkNotifiesAuthorAndCancelRevokesUnread() throws Exception {
        UUID postId = createPost(bearerB);

        mockMvc.perform(post("/api/posts/" + postId + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, bearerC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookmarked").value(true));

        mockMvc.perform(get("/api/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].type").value("POST_BOOKMARKED"));

        mockMvc.perform(post("/api/posts/" + postId + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, bearerC))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookmarked").value(false));

        mockMvc.perform(get("/api/notifications/unread-count")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(jsonPath("$.unread_count").value(0));
    }

    @Test
    void commentSoftDeleteRevokesOnlyUnreadNotification() throws Exception {
        UUID postId = createPost(bearerB);

        String commentJson = mockMvc.perform(post("/api/posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, bearerC)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"to be deleted\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String commentId = objectMapper.readTree(commentJson).get("id").asText();

        // 先读，再删 → 已读通知保留
        String listJson = mockMvc.perform(get("/api/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andReturn().getResponse().getContentAsString();
        String notificationId = objectMapper.readTree(listJson).get("items").get(0).get("id").asText();
        mockMvc.perform(post("/api/notifications/" + notificationId + "/read")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/comments/" + commentId)
                        .header(HttpHeaders.AUTHORIZATION, bearerC))
                .andExpect(status().isNoContent());

        // 已读通知不被撤销
        mockMvc.perform(get("/api/notifications")
                        .header(HttpHeaders.AUTHORIZATION, bearerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].read_at").isNotEmpty());
    }

    // ---------- helpers ----------

    private String activatedUser(String email, String name) {
        authService.register(new RegisterRequest(email, PASS, name));
        User user = userRepository.findByEmail(email).orElseThrow();
        authService.verifyEmail(user.getVerificationCode());
        return "Bearer " + tokenService.generateAccessToken(user.getId());
    }

    private UUID createPost(String bearer) throws Exception {
        String body = mockMvc.perform(post("/api/posts")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Post\",\"content\":\"c\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    /** 直接经仓储种一条通知（跳过自互动短路），返回 id 供 read 端点断言使用。 */
    private String seedForB(NotificationType type, UUID postId, UUID commentId, Instant when) {
        Notification n = Notification.create(userBId, userCId, type, postId, commentId, when);
        return notificationRepository.saveAndFlush(n).getId().toString();
    }

    private String seedForC(NotificationType type, UUID postId, UUID commentId, Instant when) {
        Notification n = Notification.create(userCId, userBId, type, postId, commentId, when);
        return notificationRepository.saveAndFlush(n).getId().toString();
    }

    private void collectIds(JsonNode envelope, Set<String> ids) {
        envelope.get("items").forEach(item -> ids.add(item.get("id").asText()));
    }
}
