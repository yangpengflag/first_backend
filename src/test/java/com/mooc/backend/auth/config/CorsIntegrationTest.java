package com.mooc.backend.auth.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 跨域配置（Task 1.1 / 1.2 / 1.5）。
 *
 * <p>前端（默认 localhost:3000）与后端（localhost:8080）不同源，
 * 无 CORS 配置时浏览器将拦截全部请求，故这是前提性阻塞项。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorsIntegrationTest {

    private static final String FRONTEND_ORIGIN = "http://localhost:3000";
    private static final String UNKNOWN_ORIGIN = "http://evil.example.com";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void preflightRequestIsAllowedForConfiguredOrigin() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", FRONTEND_ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Methods",
                        org.hamcrest.Matchers.containsString("POST")))
                .andExpect(header().string("Access-Control-Allow-Headers",
                        org.hamcrest.Matchers.containsString("authorization")))
                .andExpect(header().string("Access-Control-Allow-Headers",
                        org.hamcrest.Matchers.containsString("content-type")));
    }

    @Test
    void actualRequestCarriesAllowOriginHeader() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .header("Origin", FRONTEND_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\",\"password\":\"Str0ng!Pass\"}"))
                .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND_ORIGIN));
    }

    @Test
    void preflightIsAllowedOnProtectedEndpoint() throws Exception {
        mockMvc.perform(options("/api/auth/me")
                        .header("Origin", FRONTEND_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND_ORIGIN));
    }

    /**
     * 未配置的来源不得被回显——防止有人图省事把来源配成通配符，
     * 那样任意站点都能调用本服务接口。
     */
    @Test
    void unknownOriginIsNotEchoedBack() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .header("Origin", UNKNOWN_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\",\"password\":\"Str0ng!Pass\"}"))
                .andReturn();

        String allowOrigin = result.getResponse().getHeader("Access-Control-Allow-Origin");

        assertThat(allowOrigin).isNotEqualTo(UNKNOWN_ORIGIN);
        assertThat(allowOrigin).isNotEqualTo("*");
    }

    @Test
    void unknownOriginPreflightIsRejected() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", UNKNOWN_ORIGIN)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(result -> assertThat(
                        result.getResponse().getHeader("Access-Control-Allow-Origin"))
                        .isNotEqualTo(UNKNOWN_ORIGIN));
    }
}
