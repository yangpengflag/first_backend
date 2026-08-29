package com.mooc.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OpenAPI 文档产出与安全边界（change: openapi-integration，tasks 1-4）。
 *
 * <p>非 prod 环境下 {@code /v3/api-docs} 与 Swagger UI 必须可达，文档须覆盖
 * 认证端点与四态响应码，且<b>不得</b>出现任何凭证类字段——
 * 后者由既有白名单 DTO 结构保证（springdoc 读取的是同一批 DTO）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsTest {

    /** 凭证类字段（同时覆盖驼峰与下划线两种命名）。 */
    private static final String[] CREDENTIAL_FIELDS = {
            "passwordHash", "password_hash", "salt", "verificationCode", "verification_code"
    };

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocs_isAccessible_andDescribesAuthEndpoints() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(json).contains("openapi");
        assertThat(json).contains("/api/auth/register", "/api/auth/login", "/api/auth/verify");
    }

    @Test
    void swaggerUi_isAccessible() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    void apiDocs_neverExposesCredentialFields() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(json).doesNotContain(CREDENTIAL_FIELDS);
    }

    @Test
    void loginEndpoint_documentsFourStatusResponses() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 用户状态机四态：ACTIVE 200 / DELETED 401 / EMAIL_UNVERIFIED 403 / LOCKED 423
        assertThat(json).contains("\"200\"", "\"401\"", "\"403\"", "\"423\"");
    }
}
