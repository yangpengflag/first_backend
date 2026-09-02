package com.mooc.backend.places.repository;

import com.mooc.backend.places.domain.City;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 城市仓储测试（{@code city-module} 精简后契约）：
 * slug 唯一约束、列表按 name 升序分页、软删行被只读查询过滤且可被 seed 判重命中。
 * 运行于 {@code @Transactional}，结束自动回滚，不污染库。
 */
@SpringBootTest
@Transactional
class CityRepositoryTest {

    @Autowired
    private CityRepository cityRepository;

    @Autowired
    private EntityManager em;

    @BeforeEach
    void clean() {
        em.createNativeQuery("DELETE FROM spots").executeUpdate();
        em.createNativeQuery("DELETE FROM cities").executeUpdate();
    }

    private City makeCity(String name, String slug) {
        return City.create(UUID.randomUUID(), name, "中文名", slug, "https://img/c.jpg",
                "A city: " + name + ".", "spring", Instant.now());
    }

    @Test
    void slugIsUnique() {
        cityRepository.saveAndFlush(makeCity("Hangzhou", "hangzhou"));
        City dup = makeCity("Hangzhou", "hangzhou");
        assertThatThrownBy(() -> cityRepository.saveAndFlush(dup))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void listOrdersByNameAndPaginates() {
        cityRepository.saveAndFlush(makeCity("Xi'an", "xian"));
        cityRepository.saveAndFlush(makeCity("Hangzhou", "hangzhou"));
        cityRepository.saveAndFlush(makeCity("Shanghai", "shanghai"));

        Page<City> page = cityRepository.findByDeletedFalse(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "name")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(City::getName)
                .containsExactly("Hangzhou", "Shanghai", "Xi'an");
    }

    @Test
    void softDeletedCityExcludedFromListButHitBySeedLookup() {
        City city = makeCity("Hangzhou", "hangzhou");
        cityRepository.saveAndFlush(city);
        city.markDeleted();
        cityRepository.saveAndFlush(city);
        em.clear();

        Page<City> live = cityRepository.findByDeletedFalse(PageRequest.of(0, 10, Sort.by("name")));
        assertThat(live.getContent()).isEmpty();
        assertThat(cityRepository.findBySlugAndDeletedFalse("hangzhou")).isEmpty();
        // seed 幂等判重：软删行也视为"已存在"，不重复导入
        assertThat(cityRepository.findBySlug("hangzhou")).isPresent();
    }
}
