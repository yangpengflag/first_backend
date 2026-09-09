package com.mooc.backend.repository;

import com.mooc.backend.entity.City;
import com.mooc.backend.entity.Post;
import com.mooc.backend.entity.PostStatus;
import com.mooc.backend.entity.Spot;
import com.mooc.backend.entity.SpotCategory;
import com.mooc.backend.entity.SpotStatus;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 混合搜索关键词腿仓储测试（change: ai-semantic-search，tasks 1.1–1.3）。
 *
 * <p>三个 repository 各自的 {@code searchByKeyword(q, limit)}：LIKE 命中（中英文名 / 标题）、
 * 软删行与 DRAFT 排除、limit 生效、LIKE 通配符 {@code %} / {@code _} 转义（不注入通配）。
 * 运行于 {@code @Transactional}，结束自动回滚，不污染库。
 */
@SpringBootTest
@Transactional
class KeywordSearchRepositoryTest {

    @Autowired
    private CityRepository cityRepository;

    @Autowired
    private SpotRepository spotRepository;

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private EntityManager em;

    @BeforeEach
    void clean() {
        em.createNativeQuery("DELETE FROM posts").executeUpdate();
        em.createNativeQuery("DELETE FROM spots").executeUpdate();
        em.createNativeQuery("DELETE FROM cities").executeUpdate();
    }

    // ---- 造数辅助（沿用各实体既有测试的构造方式） ----

    private City makeCity(String name, String nameZh, String slug) {
        return City.create(UUID.randomUUID(), name, nameZh, slug, null,
                "desc", "spring", Instant.now());
    }

    private Spot makeSpot(String slug, String nameEn, String nameZh, SpotStatus status) {
        return Spot.create(UUID.randomUUID(), slug, nameZh, nameEn, "hangzhou", SpotCategory.NATURE,
                List.of(), null, null, null, 30.0, 120.0, null, List.of(),
                "en summary", "zh summary", "en desc", "zh desc", null, null, null,
                null, false, false, status, Instant.now());
    }

    private Post makePost(String title, PostStatus status) {
        return Post.create(UUID.randomUUID(), title, "content", null, List.of(), status, null, Instant.now());
    }

    // ---- 1.1 cities：LIKE name / name_zh ----

    @Test
    void citySearchMatchesEnglishAndChineseName() {
        cityRepository.saveAndFlush(makeCity("Hangzhou", "杭州", "hangzhou"));
        cityRepository.saveAndFlush(makeCity("Shanghai", "上海", "shanghai"));

        // 注意包含匹配语义："hang" 也是 "Shanghai" 的子串
        assertThat(cityRepository.searchByKeyword("hang", 10))
                .extracting(City::getSlug).containsExactly("hangzhou", "shanghai");
        assertThat(cityRepository.searchByKeyword("杭州", 10))
                .extracting(City::getSlug).containsExactly("hangzhou");
        assertThat(cityRepository.searchByKeyword("北京", 10)).isEmpty();
    }

    @Test
    void citySearchExcludesSoftDeleted() {
        City city = makeCity("Hangzhou", "杭州", "hangzhou");
        cityRepository.saveAndFlush(city);
        city.markDeleted();
        cityRepository.saveAndFlush(city);
        em.clear();

        assertThat(cityRepository.searchByKeyword("hang", 10)).isEmpty();
    }

    @Test
    void citySearchEscapesLikeWildcards() {
        // "A_B" 与 "AXB" 同时存在：未转义的 `A_B` 会把两者都命中（_ 单字符通配）
        cityRepository.saveAndFlush(makeCity("A_B", "下划线", "a-b"));
        cityRepository.saveAndFlush(makeCity("AXB", "普通", "axb"));

        assertThat(cityRepository.searchByKeyword("A_B", 10))
                .extracting(City::getSlug).containsExactly("a-b");

        // % 通配同样被转义：字面 "100%" 只命中名称含该字面量的行（"100 Percent" 无字面 % 不命中）
        cityRepository.saveAndFlush(makeCity("100% Pure", "百分百", "percent-pure"));
        assertThat(cityRepository.searchByKeyword("100%", 10))
                .extracting(City::getSlug).containsExactly("percent-pure");
        assertThat(cityRepository.searchByKeyword("100 Percent", 10)).isEmpty();
    }

    @Test
    void citySearchRespectsLimit() {
        cityRepository.saveAndFlush(makeCity("Hang A", "杭一", "hang-a"));
        cityRepository.saveAndFlush(makeCity("Hang B", "杭二", "hang-b"));
        cityRepository.saveAndFlush(makeCity("Hang C", "杭三", "hang-c"));

        assertThat(cityRepository.searchByKeyword("hang", 2)).hasSize(2);
    }

    // ---- 1.2 spots：LIKE name_en / name_zh，仅 PUBLISHED ----

    @Test
    void spotSearchMatchesEnglishAndChineseName() {
        spotRepository.saveAndFlush(makeSpot("hz-west-lake", "West Lake", "西湖", SpotStatus.PUBLISHED));
        spotRepository.saveAndFlush(makeSpot("hz-lingyin", "Lingyin Temple", "灵隐寺", SpotStatus.PUBLISHED));

        assertThat(spotRepository.searchByKeyword("lake", 10))
                .extracting(Spot::getSlug).containsExactly("hz-west-lake");
        assertThat(spotRepository.searchByKeyword("西湖", 10))
                .extracting(Spot::getSlug).containsExactly("hz-west-lake");
        assertThat(spotRepository.searchByKeyword("不存在", 10)).isEmpty();
    }

    @Test
    void spotSearchExcludesDraftAndSoftDeleted() {
        Spot draft = makeSpot("hz-draft", "Draft Lake", "草稿湖", SpotStatus.DRAFT);
        spotRepository.saveAndFlush(draft);
        Spot deleted = makeSpot("hz-deleted", "Deleted Lake", "已删湖", SpotStatus.PUBLISHED);
        spotRepository.saveAndFlush(deleted);
        deleted.markDeleted();
        spotRepository.saveAndFlush(deleted);
        spotRepository.saveAndFlush(makeSpot("hz-live", "Live Lake", "在库湖", SpotStatus.PUBLISHED));
        em.clear();

        List<Spot> hits = spotRepository.searchByKeyword("lake", 10);
        assertThat(hits).extracting(Spot::getSlug).containsExactly("hz-live");
    }

    @Test
    void spotSearchEscapesLikeWildcards() {
        spotRepository.saveAndFlush(makeSpot("hz-underscore", "A_B Lake", "下划线湖", SpotStatus.PUBLISHED));
        spotRepository.saveAndFlush(makeSpot("hz-x", "AXB Lake", "普通湖", SpotStatus.PUBLISHED));

        assertThat(spotRepository.searchByKeyword("A_B", 10))
                .extracting(Spot::getSlug).containsExactly("hz-underscore");
    }

    // ---- 1.3 posts：LIKE title，仅 PUBLISHED ----

    @Test
    void postSearchMatchesTitle() {
        postRepository.saveAndFlush(makePost("Hidden gems of Hangzhou", PostStatus.PUBLISHED));
        postRepository.saveAndFlush(makePost("Shanghai food guide", PostStatus.PUBLISHED));

        assertThat(postRepository.searchByKeyword("hidden gems", 10))
                .extracting(Post::getTitle).containsExactly("Hidden gems of Hangzhou");
        assertThat(postRepository.searchByKeyword("不存在", 10)).isEmpty();
    }

    @Test
    void postSearchExcludesDraftAndSoftDeleted() {
        postRepository.saveAndFlush(makePost("Draft guide to lake", PostStatus.DRAFT));
        Post deleted = makePost("Deleted guide to lake", PostStatus.PUBLISHED);
        postRepository.saveAndFlush(deleted);
        deleted.softDelete(Instant.now());
        postRepository.saveAndFlush(deleted);
        postRepository.saveAndFlush(makePost("Live guide to lake", PostStatus.PUBLISHED));
        em.clear();

        List<Post> hits = postRepository.searchByKeyword("lake", 10);
        assertThat(hits).extracting(Post::getTitle).containsExactly("Live guide to lake");
    }

    @Test
    void postSearchEscapesLikeWildcards() {
        postRepository.saveAndFlush(makePost("Guide A_B", PostStatus.PUBLISHED));
        postRepository.saveAndFlush(makePost("Guide AXB", PostStatus.PUBLISHED));

        assertThat(postRepository.searchByKeyword("A_B", 10))
                .extracting(Post::getTitle).containsExactly("Guide A_B");
    }

    @Test
    void postSearchRespectsLimit() {
        postRepository.saveAndFlush(makePost("Tea house one", PostStatus.PUBLISHED));
        postRepository.saveAndFlush(makePost("Tea house two", PostStatus.PUBLISHED));
        postRepository.saveAndFlush(makePost("Tea house three", PostStatus.PUBLISHED));

        assertThat(postRepository.searchByKeyword("tea house", 2)).hasSize(2);
    }
}
