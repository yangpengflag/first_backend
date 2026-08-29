package com.mooc.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * prod profile 下文档必须关闭（change: openapi-integration，task 2）。
 *
 * <p>Swagger UI 与 {@code /v3/api-docs} 一旦暴露在生产，等于把端点清单与
 * 请求样例直接摊开给攻击者，故 prod 下必须返回 404（而非 401/403——
 * 后者仍会暴露「该路径存在」这一事实）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class OpenApiProdProfileTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocs_isDisabled() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isNotFound());
    }

    @Test
    void swaggerUi_isDisabled() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isNotFound());
    }
}
