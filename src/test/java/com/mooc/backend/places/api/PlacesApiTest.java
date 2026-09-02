package com.mooc.backend.places.api;

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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 城市 / 景点 HTTP 层集成测试：覆盖公开列表、详情、未知 slug → 404（error.code），
 * 以及景点列表按城市筛选。公开端点免鉴权，直接 GET 即可。
 *
 * <p>公开读仅返回 PUBLISHED：DRAFT 景点经详情端点返回 404（与不存在同处理）。
 * 城市列表无筛选参数、默认 name 升序；出网字段为 slug/name/name_zh/cover_image/description/best_season/spot_count。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PlacesApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CityRepository cityRepository;

    @Autowired
    private SpotRepository spotRepository;

    @Autowired
    private EntityManager em;

    @BeforeEach
    void seed() {
        em.createNativeQuery("DELETE FROM spots").executeUpdate();
        em.createNativeQuery("DELETE FROM cities").executeUpdate();

        City city = City.create(UUID.randomUUID(), "Hangzhou", "杭州", "hangzhou",
                "https://img/c.jpg", "A beautiful city.", "spring", Instant.now());
        cityRepository.saveAndFlush(city);

        Spot spot = Spot.create(UUID.randomUUID(), "hangzhou-west-lake", "西湖", "West Lake", "hangzhou",
                SpotCategory.NATURE, List.of("免费", "出片"), "5A", "West Lake, Hangzhou", "杭州西湖",
                30.25, 120.14, "https://img/s.jpg", List.of("https://img/s1.jpg"),
                "West Lake summary.", "西湖简介。", "West Lake description.", "西湖详述。",
                "06:00-20:00", "free", "2h", 4.7, true, false, SpotStatus.PUBLISHED, Instant.now());
        spotRepository.saveAndFlush(spot);
    }

    @Test
    void cityListReturnsOk() throws Exception {
        mockMvc.perform(get("/api/cities").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].slug").value("hangzhou"))
                .andExpect(jsonPath("$.items[0].name").value("Hangzhou"))
                .andExpect(jsonPath("$.items[0].description").value("A beautiful city."));
    }

    @Test
    void cityDetailReturnsOkWithTopSpots() throws Exception {
        mockMvc.perform(get("/api/cities/hangzhou"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("hangzhou"))
                .andExpect(jsonPath("$.name_zh").value("杭州"))
                .andExpect(jsonPath("$.top_spots").isArray())
                .andExpect(jsonPath("$.spot_count").value(1));
    }

    @Test
    void unknownCityReturns404() throws Exception {
        mockMvc.perform(get("/api/cities/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CITY_NOT_FOUND"));
    }

    @Test
    void spotListFilterByCity() throws Exception {
        mockMvc.perform(get("/api/spots").param("city", "hangzhou"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].city_slug").value("hangzhou"))
                .andExpect(jsonPath("$.items[0].rating").value(4.7));
    }

    @Test
    void spotDetailReturnsOkWithNearby() throws Exception {
        mockMvc.perform(get("/api/spots/hangzhou-west-lake"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("hangzhou-west-lake"))
                .andExpect(jsonPath("$.category").value("NATURE"))
                .andExpect(jsonPath("$.nearby_spots").isArray())
                .andExpect(jsonPath("$.related_posts").isArray());
    }

    @Test
    void unknownSpotReturns404() throws Exception {
        mockMvc.perform(get("/api/spots/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SPOT_NOT_FOUND"));
    }

    /** 公开详情对 DRAFT 景点返回 404（与不存在同处理）。 */
    @Test
    void draftSpotDetailReturns404() throws Exception {
        Spot draft = Spot.create(UUID.randomUUID(), "hangzhou-draft-spot", "草稿", "Draft", "hangzhou",
                SpotCategory.NATURE, List.of(), null, null, null, null, null, null, List.of(),
                "en", "zh", "en", "zh", null, null, null, null, false, false, SpotStatus.DRAFT, Instant.now());
        spotRepository.saveAndFlush(draft);

        mockMvc.perform(get("/api/spots/hangzhou-draft-spot"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SPOT_NOT_FOUND"));
    }
}
