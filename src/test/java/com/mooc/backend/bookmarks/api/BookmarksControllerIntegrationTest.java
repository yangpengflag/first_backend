package com.mooc.backend.bookmarks.api;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 收藏 HTTP 层集成测试：真实上下文 + 真实令牌，覆盖 200 / 401 / 404 / 可达性占位 / 分页钳制。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BookmarksControllerIntegrationTest {

    private static final String EMAIL_A = "bookmarkerA@example.com";
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
        tokenA = activatedUser(EMAIL_A, "BM");
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
    void toggleRequiresAuth() throws Exception {
        mockMvc.perform(post("/api/posts/" + postId + "/bookmark"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void toggleOnThenOff() throws Exception {
        mockMvc.perform(post("/api/posts/" + postId + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookmarked").value(true));
        mockMvc.perform(post("/api/posts/" + postId + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookmarked").value(false));
    }

    @Test
    void toggleMissingPostReturns404() throws Exception {
        UUID missing = UUID.randomUUID();
        mockMvc.perform(post("/api/posts/" + missing + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
    }

    @Test
    void listShowsBookmarkedPostAsAvailable() throws Exception {
        publishPost(postId);
        mockMvc.perform(post("/api/posts/" + postId + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/bookmarks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].available").value(true))
                .andExpect(jsonPath("$.content[0].post").exists());
    }

    private void publishPost(UUID id) throws Exception {
        mockMvc.perform(put("/api/posts/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void listRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/bookmarks"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listSizeClampedToFifty() throws Exception {
        mockMvc.perform(get("/api/bookmarks").param("size", "200")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(50));
    }

    @Test
    void responseNeverLeaksSensitiveFields() throws Exception {
        mockMvc.perform(get("/api/bookmarks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(content().string(not(containsString("deleted_at"))));
    }

    @Test
    void statusRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/posts/" + postId + "/bookmark"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void statusTrueWhenBookmarked() throws Exception {
        mockMvc.perform(post("/api/posts/" + postId + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/posts/" + postId + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookmarked").value(true))
                .andExpect(jsonPath("$.post_id").value(postId.toString()));
    }

    @Test
    void statusFalseWhenNotBookmarked() throws Exception {
        mockMvc.perform(get("/api/posts/" + postId + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookmarked").value(false));
    }

    @Test
    void statusMissingPostReturns404() throws Exception {
        UUID missing = UUID.randomUUID();
        mockMvc.perform(get("/api/posts/" + missing + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"));
    }
}
