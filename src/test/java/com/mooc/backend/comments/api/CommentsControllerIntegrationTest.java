package com.mooc.backend.comments.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mooc.backend.auth.api.RegisterRequest;
import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.auth.service.AuthService;
import com.mooc.backend.auth.service.TokenService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 评论 HTTP 层集成测试：真实上下文 + 真实令牌，覆盖 401 / 403 / 404 / 400 / 200、
 * 两层模型校验、级联软删、分页钳制与安全边界。与 {@code PostsControllerIntegrationTest} 同构。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CommentsControllerIntegrationTest {

    private static final String EMAIL_A = "commenterA@example.com";
    private static final String EMAIL_B = "commenterB@example.com";
    private static final String PASS = "Str0ng!Pass";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenService tokenService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String tokenA;
    private UUID authorA;
    private UUID postId;

    @BeforeEach
    void setUp() throws Exception {
        tokenA = activatedUser(EMAIL_A, "Alice");
        authorA = userRepository.findByEmail(EMAIL_A).orElseThrow().getId();
        postId = createDraftPost();
    }

    private String activatedUser(String email, String name) {
        authService.register(new RegisterRequest(email, PASS, name));
        User user = userRepository.findByEmail(email).orElseThrow();
        authService.verifyEmail(user.getVerificationCode());
        return tokenService.generateAccessToken(user.getId());
    }

    private UUID createDraftPost() throws Exception {
        String body = mockMvc.perform(post("/api/posts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Post\",\"content\":\"c\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    @Test
    void createRequiresAuth() throws Exception {
        mockMvc.perform(post("/api/posts/" + postId + "/comments")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createOnMissingPostReturns404() throws Exception {
        UUID missing = UUID.randomUUID();
        mockMvc.perform(post("/api/posts/" + missing + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"hi\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
    }

    @Test
    void fullFlowCreateListReplyDeleteWithCascade() throws Exception {
        // 顶层评论
        String topBody = mockMvc.perform(post("/api/posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Top comment\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user_id").value(authorA.toString()))
                .andExpect(jsonPath("$.reply_count").value(0))
                .andReturn().getResponse().getContentAsString();
        UUID topId = UUID.fromString(objectMapper.readTree(topBody).get("id").asText());

        // 列表含该顶层评论
        mockMvc.perform(get("/api/posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(topId.toString()))
                .andExpect(jsonPath("$.content[0].content").value("Top comment"));

        // 回复
        String replyBody = mockMvc.perform(post("/api/posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"reply\",\"parent_comment_id\":\"" + topId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parent_comment_id").value(topId.toString()))
                .andReturn().getResponse().getContentAsString();
        UUID replyId = UUID.fromString(objectMapper.readTree(replyBody).get("id").asText());

        // 回复列表含该回复，且顶层评论 reply_count 已更新
        mockMvc.perform(get("/api/comments/" + topId + "/replies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(replyId.toString()));
        mockMvc.perform(get("/api/posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(jsonPath("$.content[0].reply_count").value(1));

        // 删除顶层评论级联软删回复
        mockMvc.perform(delete("/api/comments/" + topId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/comments/" + topId + "/replies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));

        // 安全边界
        mockMvc.perform(get("/api/posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(content().string(not(containsString("deleted_at"))))
                .andExpect(content().string(not(containsString("\"email\""))));
    }

    @Test
    void nestedReplyUnderReplyRejected() throws Exception {
        String topBody = mockMvc.perform(post("/api/posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Top\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID topId = UUID.fromString(objectMapper.readTree(topBody).get("id").asText());

        String replyBody = mockMvc.perform(post("/api/posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"reply\",\"parent_comment_id\":\"" + topId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID replyId = UUID.fromString(objectMapper.readTree(replyBody).get("id").asText());

        // 再以 reply 为父 → 400
        mockMvc.perform(post("/api/posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"nested\",\"parent_comment_id\":\"" + replyId + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARENT_COMMENT"));
    }

    @Test
    void crossPostReplyRejected() throws Exception {
        UUID otherPost = createDraftPost();
        String topBody = mockMvc.perform(post("/api/posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Top\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID topId = UUID.fromString(objectMapper.readTree(topBody).get("id").asText());

        mockMvc.perform(post("/api/posts/" + otherPost + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"reply\",\"parent_comment_id\":\"" + topId + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARENT_COMMENT"));
    }

    @Test
    void deleteByOtherForbiddenAndMissingNotFound() throws Exception {
        String topBody = mockMvc.perform(post("/api/posts/" + postId + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Top\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID topId = UUID.fromString(objectMapper.readTree(topBody).get("id").asText());

        String tokenB = activatedUser(EMAIL_B, "Bob");
        mockMvc.perform(delete("/api/comments/" + topId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_COMMENT_AUTHOR"));

        mockMvc.perform(delete("/api/comments/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));
    }

    @Test
    void listSizeClampedToFifty() throws Exception {
        mockMvc.perform(get("/api/posts/" + postId + "/comments").param("size", "200")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(50));
    }
}
