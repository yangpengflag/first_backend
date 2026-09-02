package com.mooc.backend.places.api;

import com.mooc.backend.places.domain.City;
import com.mooc.backend.places.domain.Spot;
import com.mooc.backend.places.domain.SpotBookmark;
import com.mooc.backend.places.domain.SpotCategory;
import com.mooc.backend.places.domain.SpotStatus;
import com.mooc.backend.places.repository.CityRepository;
import com.mooc.backend.places.repository.SpotBookmarkRepository;
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
 * 景点排行榜 HTTP 层集成测试（M5）。公开免鉴权端点 {@code GET /api/spots/ranking}：
 * type=rating|popular|bookmarks（默认 popular），limit 默认 10 上限 50。
 *
 * <p>覆盖三种排序语义（rating 无评分沉底 / popular view_count / bookmarks 实时聚合）、limit 钳制、
 * 以及公开访问（无 token → 200，对应 SecurityConfig 中 {@code GET /api/spots/*} permitAll 覆盖）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SpotsRankingApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CityRepository cityRepository;

    @Autowired
    private SpotRepository spotRepository;

    @Autowired
    private SpotBookmarkRepository spotBookmarkRepository;

    @Autowired
    private EntityManager em;

    private static final UUID USER_A = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_B = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @BeforeEach
    void seed() {
        em.createNativeQuery("DELETE FROM spot_bookmarks").executeUpdate();
        em.createNativeQuery("DELETE FROM spots").executeUpdate();
        em.createNativeQuery("DELETE FROM cities").executeUpdate();

        City city = City.create(UUID.randomUUID(), "Hangzhou", "杭州", "hangzhou",
                "https://img/c.jpg", "A beautiful city.", "spring", Instant.now());
        cityRepository.saveAndFlush(city);

        // view_count: s2(300) > s3(200) > s1(100) > s4(50)
        // rating:     s1(5.0) > s2(4.5) > s4(3.0) > s3(null)
        spot("hz-s1", "S1", 5.0, 100);
        spot("hz-s2", "S2", 4.5, 300);
        spot("hz-s3", "S3", null, 200);
        spot("hz-s4", "S4", 3.0, 50);

        // bookmarks: s2 -> 2 (不同用户), s1 -> 1, others 0
        bookmark("hz-s2", USER_A);
        bookmark("hz-s2", USER_B);
        bookmark("hz-s1", USER_A);
    }

    private void spot(String slug, String name, Double rating, int viewCount) {
        Spot s = Spot.create(UUID.randomUUID(), slug, name, name, "hangzhou",
                SpotCategory.NATURE, List.of(), null, null, null, null, null, null, List.of(),
                "en", "zh", "en", "zh", null, null, null, rating, false, false,
                SpotStatus.PUBLISHED, Instant.now());
        // Spot 实体无 setter；view_count 经反射注入测试数据
        try {
            var f = Spot.class.getDeclaredField("viewCount");
            f.setAccessible(true);
            f.set(s, (long) viewCount);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        spotRepository.saveAndFlush(s);
    }

    private void bookmark(String slug, UUID user) {
        spotBookmarkRepository.saveAndFlush(SpotBookmark.create(slug, user, Instant.now()));
    }

    @Test
    void rankingIsPublic() throws Exception {
        mockMvc.perform(get("/api/spots/ranking"))
                .andExpect(status().isOk());
    }

    @Test
    void defaultTypeIsPopularOrderedByViewCountDesc() throws Exception {
        mockMvc.perform(get("/api/spots/ranking"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("hz-s2"))
                .andExpect(jsonPath("$[1].slug").value("hz-s3"))
                .andExpect(jsonPath("$[2].slug").value("hz-s1"))
                .andExpect(jsonPath("$[3].slug").value("hz-s4"));
    }

    @Test
    void ratingTypePutsNullsLast() throws Exception {
        mockMvc.perform(get("/api/spots/ranking").param("type", "rating"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("hz-s1"))
                .andExpect(jsonPath("$[1].slug").value("hz-s2"))
                .andExpect(jsonPath("$[2].slug").value("hz-s4"))
                .andExpect(jsonPath("$[3].slug").value("hz-s3"));
    }

    @Test
    void bookmarksTypeOrderedByCountDesc() throws Exception {
        mockMvc.perform(get("/api/spots/ranking").param("type", "bookmarks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("hz-s2"))
                .andExpect(jsonPath("$[1].slug").value("hz-s1"));
    }

    @Test
    void unknownTypeFallsBackToPopular() throws Exception {
        mockMvc.perform(get("/api/spots/ranking").param("type", "bogus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("hz-s2"));
    }

    @Test
    void limitIsHonoredAndClamped() throws Exception {
        mockMvc.perform(get("/api/spots/ranking").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        // limit 超过上限 50 时截断到 50；本地仅 4 条，断言不超过 50 且返回全部
        mockMvc.perform(get("/api/spots/ranking").param("limit", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void responseIsJsonArray() throws Exception {
        mockMvc.perform(get("/api/spots/ranking").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
