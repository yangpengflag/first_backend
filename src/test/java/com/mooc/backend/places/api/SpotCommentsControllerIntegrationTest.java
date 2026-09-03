package com.mooc.backend.places.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mooc.backend.auth.api.RegisterRequest;
import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.auth.service.AuthService;
import com.mooc.backend.auth.service.TokenService;
import com.mooc.backend.places.domain.City;
import com.mooc.backend.places.domain.Spot;
import com.mooc.backend.places.domain.SpotCategory;
import com.mooc.backend.places.domain.SpotStatus;
import com.mooc.backend.places.repository.CityRepository;
import com.mooc.backend.places.repository.SpotRepository;

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
import java.util.List;
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
 * 景点评论 HTTP 层集成测试（镜像 comments.CommentsControllerIntegrationTest，postId → spotSlug）。
 *
 * <p>覆盖 401 / 403 / 404 / 400 / 200、两层模型校验、级联软删、分页钳制与安全边界。
 * 回复端点为独立的 {@code /api/spot-comments/{id}/replies}（景点评论存于独立 {@code spot_comments}
 * 表，复用帖子评论的 {@code /api/comments/{id}/replies} 会 404）。顶层评论列表经 SecurityConfig
 * 显式 {@code authenticated()} 网关，避免被景点公开读通配放行。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SpotCommentsControllerIntegrationTest {

    private static final String EMAIL_A = "spotcommenterA@example.com";
    private static final String EMAIL_B = "spotcommenterB@example.com";
    private static final String PASS = "Str0ng!Pass";
    private static final String SLUG = "hangzhou-west-lake";
    private static final String OTHER_SLUG = "hangzhou-lingyin";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private CityRepository cityRepository;

    @Autowired
    private SpotRepository spotRepository;

    @Autowired
    private EntityManager em;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String tokenA;
    private UUID authorA;

    @BeforeEach
    void setUp() {
        em.createNativeQuery("DELETE FROM spot_comments").executeUpdate();
        em.createNativeQuery("DELETE FROM spot_bookmarks").executeUpdate();
        em.createNativeQuery("DELETE FROM spots").executeUpdate();
        em.createNativeQuery("DELETE FROM cities").executeUpdate();

        tokenA = activatedUser(EMAIL_A, "Alice");
        authorA = userRepository.findByEmail(EMAIL_A).orElseThrow().getId();
        seedPublishedSpot(SLUG, "西湖", "West Lake");
    }

    private String activatedUser(String email, String name) {
        authService.register(new RegisterRequest(email, PASS, name));
        User user = userRepository.findByEmail(email).orElseThrow();
        authService.verifyEmail(user.getVerificationCode());
        return tokenService.generateAccessToken(user.getId());
    }

    private void seedPublishedSpot(String slug, String nameZh, String nameEn) {
        City city = City.create(UUID.randomUUID(), "Hangzhou", "杭州", "hangzhou",
                "https://img/c.jpg", "A beautiful city.", "spring", Instant.now());
        cityRepository.saveAndFlush(city);

        Spot spot = Spot.create(UUID.randomUUID(), slug, nameZh, nameEn, "hangzhou",
                SpotCategory.NATURE, List.of("免费", "出片"), "5A", "West Lake, Hangzhou", "杭州西湖",
                30.25, 120.14, "https://img/s.jpg", List.of("https://img/s1.jpg"),
                "summary.", "简介。", "description.", "详述。",
                "06:00-20:00", "free", "2h", 4.7, true, false, SpotStatus.PUBLISHED, Instant.now());
        spotRepository.saveAndFlush(spot);
    }

    /** 仅新增景点（城市已在 setUp 播种，避免 cities.slug 唯一约束冲突）。 */
    private void seedSpot(String slug, String nameZh, String nameEn) {
        Spot spot = Spot.create(UUID.randomUUID(), slug, nameZh, nameEn, "hangzhou",
                SpotCategory.NATURE, List.of("免费"), "5A", "desc en", "desc zh",
                30.25, 120.14, "https://img/s.jpg", List.of("https://img/s1.jpg"),
                "summary.", "简介。", "description.", "详述。",
                "06:00-20:00", "free", "2h", 4.7, true, false, SpotStatus.PUBLISHED, Instant.now());
        spotRepository.saveAndFlush(spot);
    }

    @Test
    void createRequiresAuth() throws Exception {
        mockMvc.perform(post("/api/spots/" + SLUG + "/comments")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/spots/" + SLUG + "/comments"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void repliesRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/spot-comments/" + UUID.randomUUID() + "/replies"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteRequiresAuth() throws Exception {
        mockMvc.perform(delete("/api/spot-comments/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createOnMissingSpotReturns404() throws Exception {
        mockMvc.perform(post("/api/spots/nope/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"hi\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SPOT_NOT_FOUND"));
    }

    @Test
    void fullFlowCreateListReplyDeleteWithCascade() throws Exception {
        String topBody = mockMvc.perform(post("/api/spots/" + SLUG + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Top comment\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user_id").value(authorA.toString()))
                .andExpect(jsonPath("$.reply_count").value(0))
                .andReturn().getResponse().getContentAsString();
        UUID topId = UUID.fromString(objectMapper.readTree(topBody).get("id").asText());

        mockMvc.perform(get("/api/spots/" + SLUG + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(topId.toString()))
                .andExpect(jsonPath("$.content[0].content").value("Top comment"));

        String replyBody = mockMvc.perform(post("/api/spots/" + SLUG + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"reply\",\"parent_comment_id\":\"" + topId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parent_comment_id").value(topId.toString()))
                .andReturn().getResponse().getContentAsString();
        UUID replyId = UUID.fromString(objectMapper.readTree(replyBody).get("id").asText());

        mockMvc.perform(get("/api/spot-comments/" + topId + "/replies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(replyId.toString()));
        mockMvc.perform(get("/api/spots/" + SLUG + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(jsonPath("$.content[0].reply_count").value(1));

        mockMvc.perform(delete("/api/spot-comments/" + topId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/spots/" + SLUG + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/spot-comments/" + topId + "/replies")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));

        mockMvc.perform(get("/api/spots/" + SLUG + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(content().string(not(containsString("deleted_at"))))
                .andExpect(content().string(not(containsString("\"email\""))));
    }

    @Test
    void nestedReplyUnderReplyRejected() throws Exception {
        String topBody = mockMvc.perform(post("/api/spots/" + SLUG + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Top\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID topId = UUID.fromString(objectMapper.readTree(topBody).get("id").asText());

        String replyBody = mockMvc.perform(post("/api/spots/" + SLUG + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"reply\",\"parent_comment_id\":\"" + topId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID replyId = UUID.fromString(objectMapper.readTree(replyBody).get("id").asText());

        mockMvc.perform(post("/api/spots/" + SLUG + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"nested\",\"parent_comment_id\":\"" + replyId + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARENT_COMMENT"));
    }

    @Test
    void crossSpotReplyRejected() throws Exception {
        seedSpot(OTHER_SLUG, "灵隐", "Lingyin");
        String topBody = mockMvc.perform(post("/api/spots/" + SLUG + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Top\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID topId = UUID.fromString(objectMapper.readTree(topBody).get("id").asText());

        mockMvc.perform(post("/api/spots/" + OTHER_SLUG + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"reply\",\"parent_comment_id\":\"" + topId + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PARENT_COMMENT"));
    }

    @Test
    void deleteByOtherForbiddenAndMissingNotFound() throws Exception {
        String topBody = mockMvc.perform(post("/api/spots/" + SLUG + "/comments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Top\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID topId = UUID.fromString(objectMapper.readTree(topBody).get("id").asText());

        String tokenB = activatedUser(EMAIL_B, "Bob");
        mockMvc.perform(delete("/api/spot-comments/" + topId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_COMMENT_AUTHOR"));

        mockMvc.perform(delete("/api/spot-comments/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMMENT_NOT_FOUND"));
    }

    @Test
    void listSizeClampedToFifty() throws Exception {
        mockMvc.perform(get("/api/spots/" + SLUG + "/comments").param("size", "200")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(50));
    }
}
