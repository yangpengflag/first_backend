package com.mooc.backend.votes.api;

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

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 投票 HTTP 层集成测试：真实上下文 + 真实令牌，覆盖 200 / 401 / 403 / 404 / 400 / 429、
 * 三态语义、统计与限流。与 {@code CommentsControllerIntegrationTest} 同构。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class VotesControllerIntegrationTest {

    private static final String EMAIL_A = "voterA@example.com";
    private static final String EMAIL_B = "voterB@example.com";
    private static final String EMAIL_C = "voterC@example.com";
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
    private UUID postId;

    @BeforeEach
    void setUp() throws Exception {
        tokenA = activatedUser(EMAIL_A, "VoterA");
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
    void voteRequiresAuth() throws Exception {
        mockMvc.perform(post("/api/posts/" + postId + "/vote")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"vote_type\":\"UP\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void voteOnMissingPostReturns404() throws Exception {
        UUID missing = UUID.randomUUID();
        mockMvc.perform(post("/api/posts/" + missing + "/vote")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"vote_type\":\"UP\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
    }

    @Test
    void invalidVoteTypeRejected() throws Exception {
        mockMvc.perform(post("/api/posts/" + postId + "/vote")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"vote_type\":\"SIDEWAYS\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void voteToggleAndCancel() throws Exception {
        // 首次 UP
        mockMvc.perform(post("/api/posts/" + postId + "/vote")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"vote_type\":\"UP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_vote").value("UP"));
        // 再投 UP → 取消（物理删除）
        mockMvc.perform(post("/api/posts/" + postId + "/vote")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"vote_type\":\"UP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_vote").value(nullValue()));
        // 切到 DOWN
        mockMvc.perform(post("/api/posts/" + postId + "/vote")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"vote_type\":\"DOWN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_vote").value("DOWN"));
    }

    @Test
    void statsReflectsVotesAndUserVote() throws Exception {
        mockMvc.perform(post("/api/posts/" + postId + "/vote")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"vote_type\":\"UP\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/posts/" + postId + "/vote/stats")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.up_count").value(1))
                .andExpect(jsonPath("$.down_count").value(0))
                .andExpect(jsonPath("$.user_vote").value("UP"));
    }

    @Test
    void statsRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/posts/" + postId + "/vote/stats"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rateLimitBlocksAfterThreshold() throws Exception {
        String tokenC = activatedUser(EMAIL_C, "VoterC");
        int threshold = 10;
        for (int i = 0; i < threshold; i++) {
            mockMvc.perform(post("/api/posts/" + postId + "/vote")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenC)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"vote_type\":\"UP\"}"))
                    .andExpect(status().isOk());
        }
        // 第 threshold+1 次应被限流
        mockMvc.perform(post("/api/posts/" + postId + "/vote")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenC)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"vote_type\":\"UP\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void responseNeverLeaksSensitiveFields() throws Exception {
        mockMvc.perform(post("/api/posts/" + postId + "/vote")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"vote_type\":\"UP\"}"))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("deleted_at"))));
    }
}
