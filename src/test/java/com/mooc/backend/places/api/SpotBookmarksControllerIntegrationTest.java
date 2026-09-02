package com.mooc.backend.places.api;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 景点收藏 HTTP 层集成测试：真实上下文 + 真实令牌。
 *
 * <p>核心契约：切换 / 状态查询 / 我的列表三类端点均需鉴权（无 token → 401）。
 * 其中 {@code GET /api/spots/{slug}/bookmark} 曾因被 {@code GET /api/spots/*} 公开读通配兜底而在
 * filter 层放行，本测试 {@link #statusRequiresAuth()} 固化其在 filter 层即需 token 的修复。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SpotBookmarksControllerIntegrationTest {

    private static final String EMAIL = "spotbookmarker@example.com";
    private static final String PASS = "Str0ng!Pass";
    private static final String SLUG = "hangzhou-west-lake";

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

    private String token;

    @BeforeEach
    void setUp() {
        em.createNativeQuery("DELETE FROM spot_bookmarks").executeUpdate();
        em.createNativeQuery("DELETE FROM spots").executeUpdate();
        em.createNativeQuery("DELETE FROM cities").executeUpdate();

        token = activatedUser(EMAIL, "SB");
        seedPublishedSpot();
    }

    private String activatedUser(String email, String name) {
        authService.register(new RegisterRequest(email, PASS, name));
        User user = userRepository.findByEmail(email).orElseThrow();
        authService.verifyEmail(user.getVerificationCode());
        return tokenService.generateAccessToken(user.getId());
    }

    private void seedPublishedSpot() {
        City city = City.create(UUID.randomUUID(), "Hangzhou", "杭州", "hangzhou",
                "https://img/c.jpg", "A beautiful city.", "spring", Instant.now());
        cityRepository.saveAndFlush(city);

        Spot spot = Spot.create(UUID.randomUUID(), SLUG, "西湖", "West Lake", "hangzhou",
                SpotCategory.NATURE, List.of("免费", "出片"), "5A", "West Lake, Hangzhou", "杭州西湖",
                30.25, 120.14, "https://img/s.jpg", List.of("https://img/s1.jpg"),
                "West Lake summary.", "西湖简介。", "West Lake description.", "西湖详述。",
                "06:00-20:00", "free", "2h", 4.7, true, false, SpotStatus.PUBLISHED, Instant.now());
        spotRepository.saveAndFlush(spot);
    }

    @Test
    void toggleRequiresAuth() throws Exception {
        mockMvc.perform(post("/api/spots/" + SLUG + "/bookmark"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void statusRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/spots/" + SLUG + "/bookmark"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/spot-bookmarks"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void toggleOnThenOff() throws Exception {
        mockMvc.perform(post("/api/spots/" + SLUG + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookmarked").value(true));
        mockMvc.perform(post("/api/spots/" + SLUG + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookmarked").value(false));
    }

    @Test
    void statusTrueWhenBookmarked() throws Exception {
        mockMvc.perform(post("/api/spots/" + SLUG + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/spots/" + SLUG + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spot_slug").value(SLUG))
                .andExpect(jsonPath("$.bookmarked").value(true));
    }

    @Test
    void statusFalseWhenNotBookmarked() throws Exception {
        mockMvc.perform(get("/api/spots/" + SLUG + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookmarked").value(false));
    }

    @Test
    void statusMissingSpotReturns404() throws Exception {
        mockMvc.perform(get("/api/spots/nope/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SPOT_NOT_FOUND"));
    }

    @Test
    void listHidesNonPublishedAndTotalMatchesContent() throws Exception {
        // 额外收藏一个 DRAFT 景点，验证列表仅返回 PUBLISHED 且 total == content 数量
        Spot draft = Spot.create(
                UUID.randomUUID(), "hangzhou-draft-spot", "草稿", "Draft", "hangzhou",
                SpotCategory.NATURE,
                List.<String>of(),
                null, null, null, null, null, null,
                List.<String>of(),
                null, null, null, null, null, null, null,
                null,
                false, false,
                SpotStatus.DRAFT, Instant.now());
        spotRepository.saveAndFlush(draft);

        mockMvc.perform(post("/api/spots/" + SLUG + "/bookmark").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/spots/hangzhou-draft-spot/bookmark").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/spot-bookmarks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].slug").value(SLUG));
    }

    @Test
    void listShowsBookmarkedSpot() throws Exception {
        mockMvc.perform(post("/api/spots/" + SLUG + "/bookmark")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/spot-bookmarks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].slug").value(SLUG));
    }
}
