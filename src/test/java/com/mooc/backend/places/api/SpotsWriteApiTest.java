package com.mooc.backend.places.api;

import com.mooc.backend.places.domain.City;
import com.mooc.backend.places.repository.CityRepository;
import com.mooc.backend.places.repository.SpotRepository;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 景点写 API 集成测试：POST /api/spots 需认证（401），认证后创建 201、slug 冲突 409、未知城市 404。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SpotsWriteApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CityRepository cityRepository;

    @Autowired
    private SpotRepository spotRepository;

    @Autowired
    private EntityManager em;

    @BeforeEach
    void setup() {
        em.createNativeQuery("DELETE FROM spots").executeUpdate();
        em.createNativeQuery("DELETE FROM cities").executeUpdate();
        cityRepository.saveAndFlush(City.create(UUID.randomUUID(), "Hangzhou", "杭州", "hangzhou",
                "https://img/c.jpg", "A beautiful city.", "spring", Instant.now()));
    }

    @Test
    void createRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/spots").contentType(MediaType.APPLICATION_JSON).content(validJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createPersistsAndReturns201() throws Exception {
        mockMvc.perform(post("/api/spots")
                        .with(SecurityMockMvcRequestPostProcessors.user("11111111-1111-1111-1111-111111111111"))
                        .contentType(MediaType.APPLICATION_JSON).content(validJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("hangzhou-west-lake"));
    }

    @Test
    void duplicateSlugReturns409() throws Exception {
        var auth = SecurityMockMvcRequestPostProcessors.user("11111111-1111-1111-1111-111111111111");
        mockMvc.perform(post("/api/spots").with(auth).contentType(MediaType.APPLICATION_JSON).content(validJson()))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/spots").with(auth).contentType(MediaType.APPLICATION_JSON).content(validJson()))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownCityReturns404() throws Exception {
        String json = "{\"nameEn\":\"X\",\"nameZh\":\"Y\",\"citySlug\":\"no-such\"}";
        mockMvc.perform(post("/api/spots")
                        .with(SecurityMockMvcRequestPostProcessors.user("11111111-1111-1111-1111-111111111111"))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateRequiresAuthentication() throws Exception {
        mockMvc.perform(put("/api/spots/hangzhou-west-lake")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nameEn\":\"X\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void putPartialUpdateKeepsOtherFields() throws Exception {
        var auth = SecurityMockMvcRequestPostProcessors.user("11111111-1111-1111-1111-111111111111");
        mockMvc.perform(post("/api/spots").with(auth).contentType(MediaType.APPLICATION_JSON).content(validJson()))
                .andExpect(status().isCreated());
        // 仅传 descriptionEn：其余字段保留原值，slug 不变
        mockMvc.perform(put("/api/spots/hangzhou-west-lake").with(auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"descriptionEn\":\"Updated desc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("hangzhou-west-lake"))
                .andExpect(jsonPath("$.description_en").value("Updated desc"))
                .andExpect(jsonPath("$.name_en").value("West Lake"))
                .andExpect(jsonPath("$.rating").value(4.7));
    }

    private String validJson() {
        return "{\"nameEn\":\"West Lake\",\"nameZh\":\"西湖\",\"citySlug\":\"hangzhou\",\"category\":\"NATURE\",\"rating\":4.7}";
    }
}
