package com.mooc.backend.places.service;

import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.places.api.CreateSpotRequest;
import com.mooc.backend.places.api.SpotDetail;
import com.mooc.backend.places.api.UpdateSpotRequest;
import com.mooc.backend.places.domain.City;
import com.mooc.backend.places.domain.Spot;
import com.mooc.backend.places.domain.SpotCategory;
import com.mooc.backend.places.exception.PlacesException;
import com.mooc.backend.places.repository.CityRepository;
import com.mooc.backend.places.repository.SpotRepository;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 景点写服务测试：slug 推导、城市校验、slug 冲突、局部更新（slug 不可变）。 */
@SpringBootTest
@Transactional
class SpotServiceTest {

    @Autowired
    private SpotService spotService;

    @Autowired
    private SpotRepository spotRepository;

    @Autowired
    private CityRepository cityRepository;

    @Autowired
    private EntityManager em;

    @BeforeEach
    void clean() {
        em.createNativeQuery("DELETE FROM spots").executeUpdate();
        em.createNativeQuery("DELETE FROM cities").executeUpdate();
        cityRepository.saveAndFlush(City.create(UUID.randomUUID(), "Hangzhou", "杭州", "hangzhou",
                "https://img/c.jpg", "A beautiful city.", "spring", Instant.now()));
    }

    private CreateSpotRequest validCreate() {
        return new CreateSpotRequest("West Lake", "西湖", null, null, null, null, null, null, null,
                "hangzhou", SpotCategory.NATURE, null, null, null, null, null, null, null, null, null, 4.7, false, false);
    }

    @Test
    void createDerivesSlugAndPersists() {
        SpotDetail d = spotService.create(validCreate(), Instant.now());
        assertThat(d.getSlug()).isEqualTo("hangzhou-west-lake");
        assertThat(spotRepository.findBySlug("hangzhou-west-lake")).isPresent();
    }

    @Test
    void createWithUnknownCityThrowsCityNotFound() {
        CreateSpotRequest req = new CreateSpotRequest("X", "Y", null, null, null, null, null, null, null,
                "no-such-city", SpotCategory.NATURE, null, null, null, null, null, null, null, null, null, null, false, false);
        assertThatThrownBy(() -> spotService.create(req, Instant.now()))
                .isInstanceOf(PlacesException.class)
                .extracting(e -> ((PlacesException) e).getErrorCode())
                .isEqualTo(ErrorCode.CITY_NOT_FOUND);
    }

    @Test
    void createDuplicateSlugThrowsConflict() {
        spotService.create(validCreate(), Instant.now());
        assertThatThrownBy(() -> spotService.create(validCreate(), Instant.now()))
                .isInstanceOf(PlacesException.class)
                .extracting(e -> ((PlacesException) e).getErrorCode())
                .isEqualTo(ErrorCode.SPOT_SLUG_CONFLICT);
    }

    @Test
    void updatePartialKeepsSlugAndCityImmutable() {
        spotService.create(validCreate(), Instant.now());
        spotService.update("hangzhou-west-lake",
                new UpdateSpotRequest("New Lake", null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, 4.9, true, null),
                Instant.now());
        Spot updated = spotRepository.findBySlug("hangzhou-west-lake").orElseThrow();
        assertThat(updated.getNameEn()).isEqualTo("New Lake");
        assertThat(updated.getRating()).isEqualTo(4.9);
        assertThat(updated.isFeatured()).isTrue();
        assertThat(updated.getSlug()).isEqualTo("hangzhou-west-lake"); // slug 不可变
        assertThat(updated.getCitySlug()).isEqualTo("hangzhou");        // citySlug 随 slug 绑定不可变
    }
}
