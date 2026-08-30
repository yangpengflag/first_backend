package com.mooc.backend.auth.api;

import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.auth.domain.UserStatus;
import com.mooc.backend.auth.service.LoggingMailSender;
import com.mooc.backend.auth.support.TestClockConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 前端↔后端联通的端到端证据（Task 5.1 / 5.2）。
 *
 * <p>经完整 Spring Security 过滤链（RateLimit → JwtAuth → UserStatus → Controller）
 * 走通真实 HTTP：register → (取验证码) → verify → login → me → logout。
 *
 * <p>验证/重置码默认不打印日志，故借 {@link LoggingMailSender#getSentMails()}
 * 取出内存中的一次性验证码，无需真实邮件投递即可闭环。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
@Transactional
class AuthFlowIntegrationTest {

    private static final String EMAIL = "flow@example.com";
    private static final String PASSWORD = "Str0ng!Pass";
    private static final String DISPLAY_NAME = "FlowUser";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private LoggingMailSender mailSender;
    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        mailSender.clear();
    }

    @Test
    void fullFlow_registerVerifyLoginMeLogout_succeeds() throws Exception {
        // 1) 注册 → 201，状态为 EMAIL_UNVERIFIED（不签发令牌）
        String registerBody = String.format(
                "{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"%s\"}",
                EMAIL, PASSWORD, DISPLAY_NAME);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isCreated());

        assertThat(userRepository.findByEmail(EMAIL).orElseThrow().getStatus())
                .isEqualTo(UserStatus.EMAIL_UNVERIFIED);

        // 2) 从内存邮件记录取出一次性验证码（绕过真实邮件投递）
        String code = mailSender.getSentMails().get(0).verificationCode();
        assertThat(code).isNotBlank();

        // 3) 邮箱验证（免鉴权）→ 200，状态转为 ACTIVE
        mockMvc.perform(get("/api/auth/verify").param("code", code))
                .andExpect(status().isOk());
        assertThat(userRepository.findByEmail(EMAIL).orElseThrow().getStatus())
                .isEqualTo(UserStatus.ACTIVE);

        // 4) 登录 → 200，签发 access / refresh 令牌
        String loginBody = String.format(
                "{\"email\":\"%s\",\"password\":\"%s\"}", EMAIL, PASSWORD);
        String loginJson = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        AuthTokenResponse tokens = objectMapper.readValue(loginJson, AuthTokenResponse.class);
        assertThat(tokens.getAccessToken()).isNotBlank();
        assertThat(tokens.getRefreshToken()).isNotBlank();
        assertThat(tokens.getUser().getStatus()).isEqualTo("ACTIVE");

        String bearer = "Bearer " + tokens.getAccessToken();

        // 5) 当前用户 → 200，返回一致的邮箱
        String meJson = mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UserResponse me = objectMapper.readValue(meJson, UserResponse.class);
        assertThat(me.getEmail()).isEqualTo(EMAIL);
        assertThat(me.getStatus()).isEqualTo("ACTIVE");

        // 6) 登出 → 204
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", bearer))
                .andExpect(status().isNoContent());
    }
}
