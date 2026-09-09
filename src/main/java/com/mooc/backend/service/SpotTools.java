package com.mooc.backend.service;

import com.mooc.backend.dto.response.SpotDetail;
import com.mooc.backend.dto.response.SpotListResponse;
import com.mooc.backend.dto.response.SpotSummary;
import com.mooc.backend.entity.City;
import com.mooc.backend.entity.SpotCategory;
import com.mooc.backend.exception.PlacesException;
import com.mooc.backend.repository.CityRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 景点数据的 AI 工具暴露（change: ai-spot-tools）。工具定义一次，同 JVM 零传输被对话模型调用。
 *
 * <p><b>与 RAG 注入的分工</b>（design.md D3）：RAG 每轮注入的叙述性片段负责"景点是什么"的
 * 事实锚定；本工具负责两类片段补不上的问题——① 列表 / 筛选（"成都有什么小众景点"）；
 * ② 单实体的精确字段（开放时间 / 门票 / 建议时长）。
 *
 * <p><b>只读且复用既有 Service</b>：全部经 {@link SpotService}（其内部已过滤 PUBLISHED 与软删），
 * 不新增 SQL、不绕过发布状态。<b>不触发访问计数</b>（{@code ViewCountService}）——AI 问一次不该
 * 把景点顶上热门榜，这也是不走 {@code SpotsController#get} 那条带计数路径的原因。
 *
 * <p><b>降级一律返回可读空态而非抛异常</b>（异常会变成模型无法转述的堆栈）：无命中 / 未知参数 /
 * 服务异常都落成 {@code found=false} 且 {@code hints} 带可用取值，让模型能自我纠错而不是反复瞎猜。
 */
@Component
public class SpotTools {

    private static final Logger log = LoggerFactory.getLogger(SpotTools.class);

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 8;
    private static final int SUMMARY_MAX_CHARS = 200;

    private final SpotService spotService;
    private final CityRepository cityRepository;

    public SpotTools(SpotService spotService, CityRepository cityRepository) {
        this.spotService = spotService;
        this.cityRepository = cityRepository;
    }

    /**
     * 检索已发布景点（列表 / 筛选类问法）。
     *
     * @param city     城市 slug（如 chengdu），省略则不限城市
     * @param category 分类枚举名（NATURE / CULTURE / HISTORY / FOOD / DISTRICT / LEISURE）
     * @param q        名称关键词（英文或中文名）
     * @param sort     {@code popular}（默认，热度）或 {@code hidden}（小众优先）
     * @param limit    返回条数，钳制到 1–8，默认 5
     */
    @Tool(name = "search_spots",
            description = "Search published spots on WanderChina. Use it for questions like "
                    + "'what to see in Chengdu' or 'any hidden gems in Chengdu'.")
    public SpotSearchResult searchSpots(
            @ToolParam(required = false, description = "City slug, e.g. chengdu. Omit for all cities.")
            String city,
            @ToolParam(required = false,
                    description = "Category: NATURE, CULTURE, HISTORY, FOOD, DISTRICT or LEISURE.")
            String category,
            @ToolParam(required = false,
                    description = "Optional name keyword, e.g. panda. Matches English and Chinese names.")
            String q,
            @ToolParam(required = false,
                    description = "Sort: 'popular' (default, by popularity) or 'hidden' (hidden gems first).")
            String sort,
            @ToolParam(required = false, description = "Max results, 1 to 8. Default 5.")
            Integer limit) {
        try {
            String rawCategory = trimToNull(category);
            String normalizedCategory = normalizeCategory(rawCategory);
            if (rawCategory != null && normalizedCategory == null) {
                return SpotSearchResult.empty("Unknown category '" + rawCategory + "'.", categoryHints());
            }
            String order = "hidden".equalsIgnoreCase(trimToNull(sort)) ? "hidden" : "popular";
            int size = clampLimit(limit);

            SpotListResponse response = spotService.list(trimToNull(city), normalizedCategory, null,
                    trimToNull(q), order, 1, size);
            List<SpotSearchResult.SpotHit> hits = response.getItems().stream()
                    .map(SpotTools::toHit)
                    .toList();
            if (hits.isEmpty()) {
                return SpotSearchResult.empty(
                        "No published spots match the given filters on WanderChina.", cityHints());
            }
            return SpotSearchResult.found(hits);
        } catch (RuntimeException e) {
            // 异常必须在工具内消化：抛出去会变成模型无法转述的堆栈（design.md D2）
            log.warn("search_spots failed (city={}, category={})", city, category, e);
            return SpotSearchResult.empty("Spot search is temporarily unavailable.", List.of());
        }
    }

    /**
     * 取单个已发布景点的结构化详情（开放时间 / 门票 / 建议时长等 RAG 片段易漏字段）。
     *
     * @param slug 景点 slug（如 chengdu-giant-panda-base）
     */
    @Tool(name = "get_spot_details",
            description = "Get structured details for one published spot: opening hours, tickets, "
                    + "suggested duration, address and tags.")
    public SpotDetailResult getSpotDetails(
            @ToolParam(description = "Spot slug, e.g. chengdu-giant-panda-base.")
            String slug) {
        String normalized = trimToNull(slug);
        if (normalized == null) {
            return SpotDetailResult.notFound("", slugHints());
        }
        try {
            SpotDetail detail = spotService.getBySlug(normalized.toLowerCase(Locale.ROOT));
            return SpotDetailResult.from(detail);
        } catch (PlacesException e) {
            // 未收录 / 非 PUBLISHED / 已软删：同型空态，不泄露草稿内容
            return SpotDetailResult.notFound(normalized, slugHints());
        } catch (RuntimeException e) {
            log.warn("get_spot_details failed (slug={})", normalized, e);
            return SpotDetailResult.unavailable(normalized);
        }
    }

    private static SpotSearchResult.SpotHit toHit(SpotSummary spot) {
        return new SpotSearchResult.SpotHit(spot.getSlug(), spot.getNameEn(), spot.getNameZh(),
                spot.getCitySlug(), spot.getCategory(), truncate(spot.getSummaryEn()),
                spot.isHiddenGem(), spot.getRating(), "/spots/" + spot.getSlug());
    }

    private static String normalizeCategory(String raw) {
        if (raw == null) {
            return null;
        }
        return Arrays.stream(SpotCategory.values())
                .filter(c -> c.name().equalsIgnoreCase(raw))
                .findFirst()
                .map(Enum::name)
                .orElse(null);
    }

    private static int clampLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        return value.length() <= SUMMARY_MAX_CHARS
                ? value
                : value.substring(0, SUMMARY_MAX_CHARS) + "…";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<String> categoryHints() {
        return Arrays.stream(SpotCategory.values()).map(Enum::name).toList();
    }

    /** 可用城市 slug（供模型换参重试）；取不到时降级为空提示，不影响空态语义。 */
    private List<String> cityHints() {
        try {
            return cityRepository.findByDeletedFalse(Pageable.unpaged())
                    .stream()
                    .map(City::getSlug)
                    .sorted()
                    .toList();
        } catch (RuntimeException e) {
            log.warn("Failed to load city slugs for tool hints", e);
            return List.of();
        }
    }

    private static List<String> slugHints() {
        return List.of("Spot slugs look like '{city}-{spot}', e.g. chengdu-giant-panda-base.");
    }
}
