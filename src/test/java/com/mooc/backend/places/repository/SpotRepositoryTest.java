package com.mooc.backend.places.repository;

import com.mooc.backend.places.domain.Spot;
import com.mooc.backend.places.domain.SpotCategory;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 景点仓储测试：验证 slug 唯一约束、列表多条件筛选（city / category / tag / q）、排序
 * （popular / hidden）、总数统计，以及城市 Top POI / 周边 POI / spotCount 聚合。
 * 运行于 {@code @Transactional}，结束自动回滚，不污染库。
 */
@SpringBootTest
@Transactional
class SpotRepositoryTest {

    @Autowired
    private SpotRepository spotRepository;

    @Autowired
    private EntityManager em;

    @BeforeEach
    void clean() {
        em.createNativeQuery("DELETE FROM spots").executeUpdate();
        em.createNativeQuery("DELETE FROM cities").executeUpdate();
    }

    private Spot makeSpot(String slug, String citySlug, SpotCategory category, int viewCount,
                          boolean hiddenGem, List<String> tags, String nameEn) {
        Spot spot = Spot.create(UUID.randomUUID(), slug, "景", nameEn, citySlug, category, tags,
                null, null, null, 30.0, 120.0, null, List.of(), "en", "zh",
                "en", "zh", null, null, null, null, false, hiddenGem, Instant.now());
        for (int i = 0; i < viewCount; i++) {
            spot.incrementViewCount();
        }
        return spot;
    }

    @Test
    void slugIsUnique() {
        spotRepository.saveAndFlush(makeSpot("hangzhou-west-lake", "hangzhou", SpotCategory.NATURE, 0, false, List.of(), "West Lake"));
        Spot dup = makeSpot("hangzhou-west-lake", "hangzhou", SpotCategory.NATURE, 0, false, List.of(), "West Lake");
        assertThatThrownBy(() -> spotRepository.saveAndFlush(dup))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void searchByCityAndCategory() {
        spotRepository.saveAndFlush(makeSpot("hangzhou-west-lake", "hangzhou", SpotCategory.NATURE, 5, false, List.of(), "West Lake"));
        spotRepository.saveAndFlush(makeSpot("hangzhou-lingyin", "hangzhou", SpotCategory.HISTORY, 3, false, List.of(), "Lingyin"));
        spotRepository.saveAndFlush(makeSpot("shanghai-bund", "shanghai", SpotCategory.CULTURE, 8, false, List.of(), "Bund"));

        List<Spot> hangzhou = spotRepository.search("hangzhou", null, null, null, "popular", PageRequest.of(0, 10));
        assertThat(hangzhou).extracting(Spot::getSlug).containsExactly("hangzhou-west-lake", "hangzhou-lingyin");

        List<Spot> nature = spotRepository.search("hangzhou", "nature", null, null, "popular", PageRequest.of(0, 10));
        assertThat(nature).extracting(Spot::getSlug).containsExactly("hangzhou-west-lake");

        assertThat(spotRepository.countSearch("hangzhou", null, null, null)).isEqualTo(2);
    }

    @Test
    void searchByTagAndKeyword() {
        spotRepository.saveAndFlush(makeSpot("hangzhou-west-lake", "hangzhou", SpotCategory.NATURE, 1, false, List.of("免费", "出片"), "West Lake"));

        assertThat(spotRepository.search(null, null, "免费", null, "popular", PageRequest.of(0, 10)))
                .extracting(Spot::getSlug).containsExactly("hangzhou-west-lake");
        assertThat(spotRepository.search(null, null, null, "lake", "popular", PageRequest.of(0, 10)))
                .extracting(Spot::getSlug).containsExactly("hangzhou-west-lake");
        assertThat(spotRepository.search(null, null, "不存在", null, "popular", PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    void sortByHiddenPrefersHiddenGems() {
        spotRepository.saveAndFlush(makeSpot("hidden-spot", "hangzhou", SpotCategory.NATURE, 1, true, List.of(), "Hidden"));
        spotRepository.saveAndFlush(makeSpot("popular-spot", "hangzhou", SpotCategory.NATURE, 100, false, List.of(), "Popular"));

        List<Spot> hidden = spotRepository.search("hangzhou", null, null, null, "hidden", PageRequest.of(0, 10));
        assertThat(hidden).extracting(Spot::getSlug).containsExactly("hidden-spot", "popular-spot");
    }

    @Test
    void topSpotsAndNearbyAndCount() {
        spotRepository.saveAndFlush(makeSpot("hangzhou-a", "hangzhou", SpotCategory.NATURE, 5, false, List.of(), "A"));
        Spot b = spotRepository.saveAndFlush(makeSpot("hangzhou-b", "hangzhou", SpotCategory.NATURE, 10, false, List.of(), "B"));

        List<Spot> top = spotRepository.findByCitySlugAndDeletedFalse("hangzhou",
                PageRequest.of(0, 6, Sort.by(Sort.Direction.DESC, "viewCount")));
        assertThat(top).extracting(Spot::getSlug).containsExactly("hangzhou-b", "hangzhou-a");

        List<Spot> nearby = spotRepository.findByCitySlugAndDeletedFalseAndIdNot("hangzhou", b.getId(),
                PageRequest.of(0, 6, Sort.by(Sort.Direction.DESC, "viewCount")));
        assertThat(nearby).extracting(Spot::getSlug).containsExactly("hangzhou-a");

        assertThat(spotRepository.countByCitySlugAndDeletedFalse("hangzhou")).isEqualTo(2);
    }
}
