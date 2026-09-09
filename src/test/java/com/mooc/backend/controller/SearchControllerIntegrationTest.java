package com.mooc.backend.controller;

import com.mooc.backend.entity.City;
import com.mooc.backend.entity.Post;
import com.mooc.backend.entity.PostStatus;
import com.mooc.backend.entity.Spot;
import com.mooc.backend.entity.SpotCategory;
import com.mooc.backend.entity.SpotStatus;
import com.mooc.backend.repository.CityRepository;
import com.mooc.backend.repository.PostRepository;
import com.mooc.backend.repository.SpotRepository;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 混合搜索 HTTP 层集成测试（change: ai-semantic-search，tasks 4.1 / 4.2）。
 *
 * <p>surefire 默认 kill switch（app.ai-rag.enabled=false）下向量腿缺席 → 纯关键词路径；
 * 200 信封（items/query/total/request_id）、q 校验 400、types 白名单、limit 钳制。
 * 运行于 {@code @Transactional}，结束后回滚。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SearchControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CityRepository cityRepository;

    @Autowired
    private SpotRepository spotRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private EntityManager em;

    @BeforeEach
    void seed() {
        em.createNativeQuery("DELETE FROM posts").executeUpdate();
        em.createNativeQuery("DELETE FROM spots").executeUpdate();
        em.createNativeQuery("DELETE FROM cities").executeUpdate();

        cityRepository.saveAndFlush(City.create(UUID.randomUUID(), "Hangzhou", "杭州", "hangzhou",
                null, "desc", "spring", Instant.now()));
        spotRepository.saveAndFlush(Spot.create(UUID.randomUUID(), "hz-west-lake", "西湖", "West Lake",
                "hangzhou", SpotCategory.NATURE, List.of(), null, null, null, 30.0, 120.0, null, List.of(),
                "A famous lake.", null, null, null, null, null, null,
                null, false, false, SpotStatus.PUBLISHED, Instant.now()));
        postRepository.saveAndFlush(Post.create(UUID.randomUUID(), "West Lake tea guide", "content",
                null, List.of(), PostStatus.PUBLISHED, null, Instant.now()));
    }

    @Test
    void searchReturns200EnvelopeWithKeywordResults() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "lake"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.query").value("lake"))
                .andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.request_id").exists())
                .andExpect(jsonPath("$.items[0].type").exists())
                .andExpect(jsonPath("$.items[0].key").exists())
                .andExpect(jsonPath("$.items[0].title").exists())
                .andExpect(jsonPath("$.items[0].url").exists())
                .andExpect(jsonPath("$.items[0].score").exists());
    }

    @Test
    void missingOrBlankQReturns400() throws Exception {
        mockMvc.perform(get("/api/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/search").param("q", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void overLongQReturns400() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "x".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void invalidTypesValueReturns400() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "lake").param("types", "city,foo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void typesFilterRestrictsResultTypes() throws Exception {
        mockMvc.perform(get("/api/search").param("q", "hang").param("types", "city"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("city"))
                .andExpect(jsonPath("$.items[0].key").value("hangzhou"));
    }

    @Test
    void limitIsClampedToBounds() throws Exception {
        // 超上限 → 钳到 20，正常返回不报错
        mockMvc.perform(get("/api/search").param("q", "lake").param("limit", "99"))
                .andExpect(status().isOk());
        // 下限之下 → 钳到 1
        mockMvc.perform(get("/api/search").param("q", "lake").param("limit", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }
}
