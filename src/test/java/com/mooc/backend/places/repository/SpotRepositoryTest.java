package com.mooc.backend.places.repository;

import com.mooc.backend.places.domain.Spot;
import com.mooc.backend.places.domain.SpotCategory;
import com.mooc.backend.places.domain.SpotStatus;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 景点仓储测试：验证 slug 唯一约束、列表多条件筛选（city / category / tag / q）、排序
 * （popular / hidden）、总数统计，以及城市 Top POI / 周边 POI / spotCount 聚合。
 * 公开读仅返回 PUBLISHED：DRAFT 行存在但不出现在任何公开读路径。
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
        return makeSpot(slug, citySlug, category, viewCount, hiddenGem, tags, nameEn, null);
    }

    private Spot makeSpot(String slug, String citySlug, SpotCategory category, int viewCount,
                          boolean hiddenGem, List<String> tags, String nameEn, Double rating) {
        Spot spot = Spot.create(UUID.randomUUID(), slug, "景", nameEn, citySlug, category, tags,
                null, null, null, 30.0, 120.0, null, List.of(), "en", "zh",
                "en", "zh", null, null, null, rating, false, hiddenGem, SpotStatus.PUBLISHED, Instant.now());
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

    /**
     * 排序键全等时分页必须是确定性的：{@code popular} 排序键仅 {@code view_count}，
     * 而该字段在数据集内可能大面积并列（种子数据 22 条中 21 条为 0）。
     * 缺少唯一 tie-breaker 时 MySQL 不保证顺序，offset 分页的相邻页会返回重叠行或永久漏行。
     *
     * <p>插入顺序刻意与 slug 字母序不同，使「碰巧有序」无法掩盖缺失的 tie-breaker。
     */
    @Test
    void popularPaginationIsDeterministicWhenSortKeysTie() {
        // 插入顺序：ccc → aaa → eee → bbb → ddd（与期望的 slug 升序不同）
        for (String slug : List.of("tie-ccc", "tie-aaa", "tie-eee", "tie-bbb", "tie-ddd")) {
            spotRepository.saveAndFlush(makeSpot(slug, "hangzhou", SpotCategory.NATURE, 0, false, List.of(), slug));
        }

        List<String> collected = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            spotRepository.search("hangzhou", null, null, null, "popular", PageRequest.of(page, 2))
                    .forEach(spot -> collected.add(spot.getSlug()));
        }

        assertThat(collected).hasSize(5)
                .doesNotHaveDuplicates()
                .containsExactly("tie-aaa", "tie-bbb", "tie-ccc", "tie-ddd", "tie-eee");
    }

    /**
     * 同 {@link #popularPaginationIsDeterministicWhenSortKeysTie()}，覆盖 {@code hidden} 排序：
     * 当 {@code hidden_gem} 与 {@code view_count} 双双并列时，分页同样必须确定。
     *
     * <p>期望顺序刻意构造为「先按 hidden_gem 分组、组内按 slug 升序」，
     * 因此它既不等于插入顺序、也**不等于全局 slug 升序**——
     * 即便优化器恰好选择 slug 索引扫描（那会返回全局 slug 序），本测试仍会失败。
     */
    @Test
    void hiddenPaginationIsDeterministicWhenSortKeysTie() {
        // 插入顺序刻意打乱；hidden_gem 分两组，使期望顺序不等于全局 slug 升序
        spotRepository.saveAndFlush(makeSpot("tie-ccc", "hangzhou", SpotCategory.NATURE, 0, true, List.of(), "tie-ccc"));
        spotRepository.saveAndFlush(makeSpot("tie-aaa", "hangzhou", SpotCategory.NATURE, 0, true, List.of(), "tie-aaa"));
        spotRepository.saveAndFlush(makeSpot("tie-eee", "hangzhou", SpotCategory.NATURE, 0, true, List.of(), "tie-eee"));
        spotRepository.saveAndFlush(makeSpot("tie-bbb", "hangzhou", SpotCategory.NATURE, 0, false, List.of(), "tie-bbb"));
        spotRepository.saveAndFlush(makeSpot("tie-ddd", "hangzhou", SpotCategory.NATURE, 0, false, List.of(), "tie-ddd"));

        List<String> collected = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            spotRepository.search("hangzhou", null, null, null, "hidden", PageRequest.of(page, 2))
                    .forEach(spot -> collected.add(spot.getSlug()));
        }

        // hidden_gem=true 组（slug 升序）在前，hidden_gem=false 组（slug 升序）在后
        assertThat(collected).hasSize(5)
                .doesNotHaveDuplicates()
                .containsExactly("tie-aaa", "tie-ccc", "tie-eee", "tie-bbb", "tie-ddd");
    }

    @Test
    void topSpotsAndNearbyAndCount() {
        spotRepository.saveAndFlush(makeSpot("hangzhou-a", "hangzhou", SpotCategory.NATURE, 5, false, List.of(), "A"));
        Spot b = spotRepository.saveAndFlush(makeSpot("hangzhou-b", "hangzhou", SpotCategory.NATURE, 10, false, List.of(), "B"));

        List<Spot> top = spotRepository.findByCitySlugAndStatusAndDeletedFalse("hangzhou", SpotStatus.PUBLISHED,
                PageRequest.of(0, 6, Sort.by(Sort.Direction.DESC, "viewCount")));
        assertThat(top).extracting(Spot::getSlug).containsExactly("hangzhou-b", "hangzhou-a");

        List<Spot> nearby = spotRepository.findByCitySlugAndStatusAndDeletedFalseAndIdNot("hangzhou", SpotStatus.PUBLISHED, b.getId(),
                PageRequest.of(0, 6, Sort.by(Sort.Direction.DESC, "viewCount")));
        assertThat(nearby).extracting(Spot::getSlug).containsExactly("hangzhou-a");

        assertThat(spotRepository.countByCitySlugAndStatusAndDeletedFalse("hangzhou", SpotStatus.PUBLISHED)).isEqualTo(2);
    }

    /**
     * 排行榜 {@code rating} 分支：无评分沉底，但**组内**仍需唯一 tie-breaker。
     * 期望顺序刻意构造为「有评分组（rating 降序）→ 无评分组（slug 升序）」，
     * 故既不等于插入顺序、也不等于全局 slug 升序，即便优化器碰巧走 slug 索引也会失败。
     */
    @Test
    void rankingIsDeterministicWhenRatingsTie() {
        // 插入顺序刻意打乱；两组内部均并列
        spotRepository.saveAndFlush(makeSpot("tie-ccc", "hangzhou", SpotCategory.NATURE, 0, false, List.of(), "tie-ccc", 4.5));
        spotRepository.saveAndFlush(makeSpot("tie-eee", "hangzhou", SpotCategory.NATURE, 0, false, List.of(), "tie-eee", null));
        spotRepository.saveAndFlush(makeSpot("tie-aaa", "hangzhou", SpotCategory.NATURE, 0, false, List.of(), "tie-aaa", 4.5));
        spotRepository.saveAndFlush(makeSpot("tie-ddd", "hangzhou", SpotCategory.NATURE, 0, false, List.of(), "tie-ddd", null));
        spotRepository.saveAndFlush(makeSpot("tie-bbb", "hangzhou", SpotCategory.NATURE, 0, false, List.of(), "tie-bbb", null));

        List<String> first = spotRepository.ranking("rating", PageRequest.of(0, 10))
                .stream().map(Spot::getSlug).toList();
        List<String> second = spotRepository.ranking("rating", PageRequest.of(0, 10))
                .stream().map(Spot::getSlug).toList();

        // 有评分组（rating 相同，按 slug 升序）在前，无评分组（按 slug 升序）沉底
        assertThat(first).containsExactly("tie-aaa", "tie-ccc", "tie-bbb", "tie-ddd", "tie-eee");
        // 重复调用结果必须完全一致（确定性）
        assertThat(second).containsExactlyElementsOf(first);
    }

    /** 公开列表 / 城市 Top POI 仅返回 PUBLISHED：DRAFT 行存在但不出现在任何公开读路径。 */
    @Test
    void draftSpotExcludedFromPublicReads() {
        Spot draft = Spot.create(UUID.randomUUID(), "hangzhou-draft", "草稿", "Draft", "hangzhou",
                SpotCategory.NATURE, List.of(), null, null, null, null, null, null, List.of(),
                "en", "zh", "en", "zh", null, null, null, null, false, false, SpotStatus.DRAFT, Instant.now());
        spotRepository.saveAndFlush(draft);
        em.clear();

        List<Spot> publicSearch = spotRepository.search("hangzhou", null, null, null, "popular", PageRequest.of(0, 10));
        assertThat(publicSearch).noneMatch(s -> s.getSlug().equals("hangzhou-draft"));
        // 草稿行本身存在（仅不公开展示）
        assertThat(spotRepository.findBySlugAndDeletedFalse("hangzhou-draft")).isPresent();
        assertThat(spotRepository.findByCitySlugAndStatusAndDeletedFalse("hangzhou", SpotStatus.PUBLISHED,
                PageRequest.of(0, 6, Sort.by(Sort.Direction.DESC, "viewCount"))))
                .noneMatch(s -> s.getSlug().equals("hangzhou-draft"));
    }
}
