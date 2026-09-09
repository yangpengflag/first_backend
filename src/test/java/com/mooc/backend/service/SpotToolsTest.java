package com.mooc.backend.service;

import com.mooc.backend.dto.response.SpotDetail;
import com.mooc.backend.dto.response.SpotListResponse;
import com.mooc.backend.dto.response.SpotSummary;
import com.mooc.backend.entity.City;
import com.mooc.backend.exception.ErrorCode;
import com.mooc.backend.exception.PlacesException;
import com.mooc.backend.repository.CityRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 景点工具（change: ai-spot-tools，task 2.1）：只读口径、参数钳制与归一化、
 * 精简字段、空态优先（异常不得变成堆栈交给模型）。全部桩 {@code SpotService}，不出网、不连库。
 */
class SpotToolsTest {

    private SpotService spotService;
    private CityRepository cityRepository;
    private SpotTools tools;

    @BeforeEach
    void setUp() {
        spotService = mock(SpotService.class);
        cityRepository = mock(CityRepository.class);
        tools = new SpotTools(spotService, cityRepository);
    }

    private static SpotSummary spot(String slug, String nameEn, String citySlug,
                                    String category, boolean hiddenGem) {
        SpotSummary summary = mock(SpotSummary.class);
        when(summary.getSlug()).thenReturn(slug);
        when(summary.getNameEn()).thenReturn(nameEn);
        when(summary.getNameZh()).thenReturn(nameEn + "-zh");
        when(summary.getCitySlug()).thenReturn(citySlug);
        when(summary.getCategory()).thenReturn(category);
        when(summary.getSummaryEn()).thenReturn("A ".repeat(150));
        when(summary.isHiddenGem()).thenReturn(hiddenGem);
        when(summary.getRating()).thenReturn(4.5d);
        return summary;
    }

    private void givenSearchResult(List<SpotSummary> items) {
        when(spotService.list(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(SpotListResponse.of(items, 1, 5, items.size()));
    }

    @Test
    void returnsTrimmedHitsWithSiteRelativeUrls() {
        givenSearchResult(List.of(spot("chengdu-giant-panda-base", "Giant Panda Base", "chengdu",
                "NATURE", true)));

        SpotSearchResult result = tools.searchSpots("chengdu", null, null, null, null);

        assertThat(result.found()).isTrue();
        SpotSearchResult.SpotHit hit = result.spots().get(0);
        assertThat(hit.slug()).isEqualTo("chengdu-giant-panda-base");
        assertThat(hit.nameEn()).isEqualTo("Giant Panda Base");
        assertThat(hit.citySlug()).isEqualTo("chengdu");
        assertThat(hit.url()).isEqualTo("/spots/chengdu-giant-panda-base");
        assertThat(hit.hiddenGem()).isTrue();
        // 摘要必须截断，避免长文本挤占上下文预算
        assertThat(hit.summary()).hasSizeLessThanOrEqualTo(201);
        assertThat(hit.rating()).isEqualTo(4.5d);
    }

    @Test
    void limitIsClampedAndDefaultsToFive() {
        givenSearchResult(List.of(spot("s1", "S1", "chengdu", "NATURE", false)));

        tools.searchSpots(null, null, null, null, 50);
        verify(spotService).list(isNull(), isNull(), isNull(), isNull(), eq("popular"), eq(1), eq(8));

        tools.searchSpots(null, null, null, null, null);
        verify(spotService).list(isNull(), isNull(), isNull(), isNull(), eq("popular"), eq(1), eq(5));
    }

    @Test
    void hiddenSortIsForwardedAndUnknownSortFallsBackToPopular() {
        givenSearchResult(List.of(spot("s1", "S1", "chengdu", "NATURE", true)));

        tools.searchSpots("chengdu", null, null, "hidden", 3);
        verify(spotService).list(eq("chengdu"), isNull(), isNull(), isNull(), eq("hidden"), eq(1), eq(3));

        tools.searchSpots("chengdu", null, null, "bogus", 3);
        verify(spotService).list(eq("chengdu"), isNull(), isNull(), isNull(), eq("popular"), eq(1), eq(3));
    }

    @Test
    void categoryIsNormalizedToEnumName() {
        givenSearchResult(List.of(spot("s1", "S1", "chengdu", "CULTURE", false)));

        tools.searchSpots(null, " culture ", null, null, null);

        verify(spotService).list(isNull(), eq("CULTURE"), isNull(), isNull(), eq("popular"), eq(1), eq(5));
    }

    @Test
    void unknownCategoryYieldsEmptyStateWithValidHints() {
        SpotSearchResult result = tools.searchSpots(null, "BEACH", null, null, null);

        assertThat(result.found()).isFalse();
        assertThat(result.spots()).isEmpty();
        assertThat(result.hints()).contains("NATURE", "CULTURE", "HISTORY", "FOOD", "DISTRICT", "LEISURE");
    }

    @Test
    void noMatchYieldsEmptyStateWithAvailableCitySlugs() {
        givenSearchResult(List.of());
        City city = mock(City.class);
        when(city.getSlug()).thenReturn("chengdu");
        Page<City> page = new PageImpl<>(List.of(city));
        when(cityRepository.findByDeletedFalse(any(Pageable.class))).thenReturn(page);

        SpotSearchResult result = tools.searchSpots("atlantis", null, null, null, null);

        assertThat(result.found()).isFalse();
        assertThat(result.hints()).containsExactly("chengdu");
    }

    @Test
    void serviceFailureIsSwallowedIntoReadableEmptyState() {
        when(spotService.list(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("db is down"));

        SpotSearchResult result = tools.searchSpots("chengdu", null, null, null, null);

        assertThat(result.found()).isFalse();
        assertThat(result.message()).contains("temporarily unavailable");
        // 异常与堆栈绝不能进入模型上下文
        assertThat(result.message()).doesNotContain("RuntimeException", "db is down");
    }

    @Test
    void detailsExposeStructuredFields() {
        SpotDetail detail = mock(SpotDetail.class);
        when(detail.getSlug()).thenReturn("hangzhou-west-lake");
        when(detail.getNameEn()).thenReturn("West Lake");
        when(detail.getNameZh()).thenReturn("西湖");
        when(detail.getSummaryEn()).thenReturn("A lake.");
        when(detail.getOpeningHours()).thenReturn("24h");
        when(detail.getTicketInfo()).thenReturn("Free");
        when(detail.getVisitDuration()).thenReturn("2-3h");
        when(detail.getAddressEn()).thenReturn("Hangzhou");
        when(detail.getTags()).thenReturn(List.of("nature"));
        when(spotService.getBySlug("hangzhou-west-lake")).thenReturn(detail);

        SpotDetailResult result = tools.getSpotDetails("Hangzhou-West-Lake ");

        assertThat(result.found()).isTrue();
        assertThat(result.openingHours()).isEqualTo("24h");
        assertThat(result.ticketInfo()).isEqualTo("Free");
        assertThat(result.visitDuration()).isEqualTo("2-3h");
        assertThat(result.url()).isEqualTo("/spots/hangzhou-west-lake");
        assertThat(result.tags()).containsExactly("nature");
    }

    @Test
    void unknownSlugYieldsNotFoundEmptyState() {
        when(spotService.getBySlug(anyString()))
                .thenThrow(new PlacesException(ErrorCode.SPOT_NOT_FOUND));

        SpotDetailResult result = tools.getSpotDetails("no-such-spot");

        assertThat(result.found()).isFalse();
        assertThat(result.message()).contains("No published spot");
        assertThat(result.hints()).isNotEmpty();
    }

    @Test
    void detailsFailureYieldsUnavailableEmptyState() {
        when(spotService.getBySlug(anyString())).thenThrow(new RuntimeException("db exploded"));

        SpotDetailResult result = tools.getSpotDetails("hangzhou-west-lake");

        assertThat(result.found()).isFalse();
        assertThat(result.message()).doesNotContain("db exploded");
    }

    @Test
    void blankSlugIsRejectedWithoutCallingTheService() {
        SpotDetailResult result = tools.getSpotDetails("   ");

        assertThat(result.found()).isFalse();
        verify(spotService, org.mockito.Mockito.never()).getBySlug(anyString());
    }
}
