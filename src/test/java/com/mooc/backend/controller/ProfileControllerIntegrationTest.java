package com.mooc.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mooc.backend.dto.RegisterRequest;
import com.mooc.backend.entity.User;
import com.mooc.backend.repository.UserRepository;
import com.mooc.backend.service.AuthService;
import com.mooc.backend.service.TokenService;
import com.mooc.backend.TestClockConfiguration;
import com.mooc.backend.dto.response.ProfileResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 档案 HTTP 层集成测试（Task 2.3）。
 *
 * <p>经完整 Spring Security 过滤链（RateLimit → JwtAuth → UserStatus → Controller）
 * 走真实 HTTP：200 白名单字段精确断言、401 门禁、4xx 后数据库不变、tags 去重、
 * {@code {}} 幂等。
 *
 * <p>用户激活沿用 {@code BookmarksControllerIntegrationTest} 的 service 直调模式：
 * 走 HTTP 注册会消耗内存态限流器配额（{@code RateLimitFilter} 不随 {@code @Transactional}
 * 回滚），多测试方法下会触发 429 干扰断言；令牌本身仍由真实 {@code TokenService} 签发，
 * 过滤链真实性不受影响。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
@Transactional
class ProfileControllerIntegrationTest {

    private static final String EMAIL = "profile@example.com";
    private static final String PASSWORD = "Str0ng!Pass";
    private static final String DISPLAY_NAME = "ProfileUser";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenService tokenService;

    private String bearer;

    @BeforeEach
    void setUp() {
        authService.register(new RegisterRequest(EMAIL, PASSWORD, DISPLAY_NAME));
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        authService.verifyEmail(user.getVerificationCode());
        bearer = "Bearer " + tokenService.generateAccessToken(user.getId());
    }

    // ---------- GET /api/users/me ----------

    @Test
    void getProfileReturnsExactlyWhitelistedFields() throws Exception {
        String json = mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode node = objectMapper.readTree(json);
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);

        // 多一字段或少一字段均视为违约（含 request_id）
        assertThat(names).containsExactlyInAnyOrderElementsOf(ProfileResponse.WHITELISTED_FIELDS);
    }

    @Test
    void getProfileBioAndTagsAreNullWhenNeverSet() throws Exception {
        String json = mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.display_name").value(DISPLAY_NAME))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andReturn().getResponse().getContentAsString();

        // 「未设置」输出显式 null 而非字段缺失
        JsonNode node = objectMapper.readTree(json);
        assertThat(node.hasNonNull("bio")).isFalse();
        assertThat(node.has("bio")).isTrue();
        assertThat(node.get("bio").isNull()).isTrue();
        assertThat(node.hasNonNull("tags")).isFalse();
        assertThat(node.has("tags")).isTrue();
        assertThat(node.get("tags").isNull()).isTrue();
    }

    // ---------- PATCH /api/users/me ----------

    @Test
    void patchUpdatesProfileAndPersists() throws Exception {
        String body = "{\"displayName\":\"  New Name \","
                + "\"avatarUrl\":\"https://cdn.example.com/a.png\","
                + "\"bio\":\"hello world\","
                + "\"tags\":[\"Hiking\",\"Travel\"]}";
        mockMvc.perform(patch("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.display_name").value("New Name"))
                .andExpect(jsonPath("$.avatar_url").value("https://cdn.example.com/a.png"))
                .andExpect(jsonPath("$.bio").value("hello world"))
                .andExpect(jsonPath("$.tags.length()").value(2));

        // 再 GET 验证持久化
        JsonNode node = objectMapper.readTree(getProfileJson());
        assertThat(node.get("display_name").asText()).isEqualTo("New Name");
        assertThat(node.get("bio").asText()).isEqualTo("hello world");

        // users 表对应行确已写入
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        assertThat(user.getDisplayName()).isEqualTo("New Name");
        assertThat(user.getBio()).isEqualTo("hello world");
        assertThat(user.getTags()).containsExactly("Hiking", "Travel");
    }

    @Test
    void patchRejectsOversizedBioAndLeavesDbUnchanged() throws Exception {
        patchBio("original bio");

        String body = "{\"bio\":\"" + "x".repeat(501) + "\"}";
        mockMvc.perform(patch("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(objectMapper.readTree(getProfileJson()).get("bio").asText()).isEqualTo("original bio");
    }

    @Test
    void patchRejectsNineTagsAndLeavesDbUnchanged() throws Exception {
        patchTags("Original");

        String body = "{\"tags\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\"]}";
        mockMvc.perform(patch("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        JsonNode node = objectMapper.readTree(getProfileJson());
        assertThat(node.get("tags").size()).isEqualTo(1);
        assertThat(node.get("tags").get(0).asText()).isEqualTo("Original");
    }

    @Test
    void patchRejectsJavascriptAvatarUrlAndLeavesDbUnchanged() throws Exception {
        patchAvatarUrl("https://cdn.example.com/ok.png");

        String body = "{\"avatarUrl\":\"javascript:alert(1)\"}";
        mockMvc.perform(patch("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(objectMapper.readTree(getProfileJson()).get("avatar_url").asText())
                .isEqualTo("https://cdn.example.com/ok.png");
    }

    @Test
    void patchDeduplicatesTagsCaseInsensitively() throws Exception {
        String body = "{\"tags\":[\"Hiking\",\"hiking \",\"HIKING\"]}";
        mockMvc.perform(patch("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        JsonNode node = objectMapper.readTree(getProfileJson());
        assertThat(node.get("tags").size()).isEqualTo(1);
        assertThat(node.get("tags").get(0).asText()).isEqualTo("Hiking");

        assertThat(userRepository.findByEmail(EMAIL).orElseThrow().getTags()).containsExactly("Hiking");
    }

    @Test
    void patchEmptyObjectKeepsAllFields() throws Exception {
        String full = "{\"displayName\":\"New Name\",\"avatarUrl\":\"https://cdn.example.com/a.png\","
                + "\"bio\":\"keep me\",\"tags\":[\"Hiking\"]}";
        mockMvc.perform(patch("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(full))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.display_name").value("New Name"))
                .andExpect(jsonPath("$.avatar_url").value("https://cdn.example.com/a.png"))
                .andExpect(jsonPath("$.bio").value("keep me"))
                .andExpect(jsonPath("$.tags.length()").value(1));
    }

    // ---------- 状态门禁 ----------

    @Test
    void unauthenticatedGetReturns401() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void unauthenticatedPatchReturns401() throws Exception {
        mockMvc.perform(patch("/api/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    // ---------- helpers ----------

    private String getProfileJson() throws Exception {
        return mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void patchBio(String bio) throws Exception {
        mockMvc.perform(patch("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bio\":\"" + bio + "\"}"))
                .andExpect(status().isOk());
    }

    private void patchAvatarUrl(String avatarUrl) throws Exception {
        mockMvc.perform(patch("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"avatarUrl\":\"" + avatarUrl + "\"}"))
                .andExpect(status().isOk());
    }

    private void patchTags(String tag) throws Exception {
        mockMvc.perform(patch("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[\"" + tag + "\"]}"))
                .andExpect(status().isOk());
    }
}
