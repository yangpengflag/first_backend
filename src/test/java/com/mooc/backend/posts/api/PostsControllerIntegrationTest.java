package com.mooc.backend.posts.api;

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
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 帖子 HTTP 层集成测试（与 {@code UserStatusFilterHttpTest} 同构）：
 * 真实上下文 + 真实令牌，覆盖 401 / 403 / 404 / 200、公开列表仅 PUBLISHED、
 * 作者信息、summary 派生、size 钳制与安全边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PostsControllerIntegrationTest {

    private static final String EMAIL_A = "authora@example.com";
    private static final String EMAIL_B = "authorb@example.com";
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

    @BeforeEach
    void setUp() {
        tokenA = activatedUser(EMAIL_A, "Alice");
        authorA = userRepository.findByEmail(EMAIL_A).orElseThrow().getId();
    }

    private String activatedUser(String email, String name) {
        authService.register(new RegisterRequest(email, PASS, name));
        User user = userRepository.findByEmail(email).orElseThrow();
        authService.verifyEmail(user.getVerificationCode());
        return tokenService.generateAccessToken(user.getId());
    }

    @Test
    void createRequiresAuth() throws Exception {
        mockMvc.perform(post("/api/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"content\":\"C\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fullFlowCreatePublishListDetail() throws Exception {
        String createJson = "{\"title\":\"Chengdu hikes\",\"content\":\"# Top\\nThis is **great**.\","
                + "\"tags\":[\"Hiking\",\"Sichuan\"],\"status\":\"DRAFT\"}";
        String body = mockMvc.perform(post("/api/posts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content(createJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.author_id").value(authorA.toString()))
                .andExpect(jsonPath("$.author_name").value("Alice"))
                .andReturn().getResponse().getContentAsString();
        UUID postId = extractId(body);

        // 草稿不进公开列表（offset 模式用 sort=top 以拿到 total）
        mockMvc.perform(get("/api/posts?sort=top"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        // 草稿详情 404
        mockMvc.perform(get("/api/posts/" + postId)).andExpect(status().isNotFound());

        // 发布
        mockMvc.perform(put("/api/posts/" + postId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.summary").value(startsWith("Top This is great")));

        // 发布后进入公开列表
        mockMvc.perform(get("/api/posts?sort=top"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].author_name").value("Alice"))
                .andExpect(jsonPath("$.items[0].author_id").value(authorA.toString()));

        // 公开详情：派生 summary，且不含 deleted_at / email
        mockMvc.perform(get("/api/posts/" + postId))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("deleted_at"))))
                .andExpect(content().string(not(containsString("\"email\""))));
    }

    @Test
    void sizeClampedToHundred() throws Exception {
        mockMvc.perform(get("/api/posts?sort=top").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void myPostsRequiresAuthAndShowsDrafts() throws Exception {
        mockMvc.perform(post("/api/posts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Mine\",\"content\":\"c\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/posts/me")).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/posts/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("Mine"));
    }

    @Test
    void editByOtherAuthorForbidden() throws Exception {
        String body = mockMvc.perform(post("/api/posts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"content\":\"c\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID postId = extractId(body);

        String tokenB = activatedUser(EMAIL_B, "Bob");
        mockMvc.perform(put("/api/posts/" + postId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_POST_AUTHOR"));
    }

    @Test
    void deleteSoftRemovesPost() throws Exception {
        String body = mockMvc.perform(post("/api/posts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"ToDelete\",\"content\":\"c\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID postId = extractId(body);

        // 未带令牌删除 → 401
        mockMvc.perform(delete("/api/posts/" + postId))
                .andExpect(status().isUnauthorized());

        // 他人删除 → 403
        String tokenB = activatedUser(EMAIL_B, "Bob");
        mockMvc.perform(delete("/api/posts/" + postId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_POST_AUTHOR"));

        // 作者删除 → 204，且从详情消失
        mockMvc.perform(delete("/api/posts/" + postId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/posts/" + postId)).andExpect(status().isNotFound());
    }

    private UUID extractId(String json) throws Exception {
        return UUID.fromString(objectMapper.readTree(json).get("id").asText());
    }
}
