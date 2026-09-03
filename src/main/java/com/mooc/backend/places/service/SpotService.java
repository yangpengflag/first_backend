package com.mooc.backend.places.service;

import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.places.api.CreateSpotRequest;
import com.mooc.backend.places.api.SpotDetail;
import com.mooc.backend.places.api.SpotListResponse;
import com.mooc.backend.places.api.SpotSummary;
import com.mooc.backend.places.api.UpdateSpotRequest;
import com.mooc.backend.places.domain.CitySlugs;
import com.mooc.backend.places.domain.Spot;
import com.mooc.backend.places.domain.SpotCategory;
import com.mooc.backend.places.domain.SpotStatus;
import com.mooc.backend.places.exception.PlacesException;
import com.mooc.backend.places.repository.CityRepository;
import com.mooc.backend.places.repository.SpotRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 景点读 + 写服务。
 *
 * <p>读：列表做城市 / 分类 / 标签 / 关键词筛选、排序、分页；详情组装周边 POI 与相关攻略占位；
 * 公开读仅返回 PUBLISHED（DRAFT 详情 404）。
 *
 * <p>写（POST / PUT）：需认证（控制器层校验），但 creator 不持久化（CMS POI 无需归属）。
 * 创建校验城市存在（CITY_NOT_FOUND），slug 由 {@code {citySlug}-{slugify(nameEn)}} 推导，
 * 冲突 → SPOT_SLUG_CONFLICT (409)；slug / citySlug 创建后不可变（更新不重算）。
 * 路由不触发 viewCount（仅详情 GET 触发）。
 */
@Service
public class SpotService {

    private final SpotRepository spotRepository;
    private final CityRepository cityRepository;
    private final RankingCacheService rankingCacheService;

    public SpotService(SpotRepository spotRepository, CityRepository cityRepository,
                       RankingCacheService rankingCacheService) {
        this.spotRepository = spotRepository;
        this.cityRepository = cityRepository;
        this.rankingCacheService = rankingCacheService;
    }

    public SpotListResponse list(String city, String category, String tag, String q, String sort,
                                int page, int size) {
        var pageable = PageRequest.of(Math.max(0, page - 1), size);
        List<Spot> spots = spotRepository.search(city, category, tag, q, sort, pageable);
        long total = spotRepository.countSearch(city, category, tag, q);
        List<SpotSummary> items = spots.stream().map(SpotSummary::from).toList();
        return SpotListResponse.of(items, page, size, total);
    }

    /**
     * 景点排行榜：仅 PUBLISHED。type 非法时回退 popular；limit 钳制到 [1, 50]。
     * 返回截断后的 {@code SpotSummary} 列表（无分页包装，纯 Top N 数组）。
     *
     * <p>委托 {@link RankingCacheService}（Cache-Aside，change: add-spot-ranking-redis-cache）：
     * 命中返回缓存快照（TTL 5 分钟内新鲜，写操作/收藏切换即时失效）；Redis 不可用或缓存禁用时
     * 直查数据库，行为与原实现等价。
     */
    public List<SpotSummary> ranking(String type, int limit) {
        return rankingCacheService.getRanking(type, limit);
    }

    public SpotDetail getBySlug(String slug) {
        Spot spot = spotRepository.findBySlugAndDeletedFalse(slug)
                .orElseThrow(() -> new PlacesException(ErrorCode.SPOT_NOT_FOUND));
        if (spot.getStatus() != SpotStatus.PUBLISHED) {
            throw new PlacesException(ErrorCode.SPOT_NOT_FOUND);
        }
        return toDetail(spot);
    }

    /** 创建景点：校验城市存在；slug 推导，冲突 → 409。 */
    public SpotDetail create(CreateSpotRequest request, Instant now) {
        if (cityRepository.findBySlugAndDeletedFalse(request.citySlug()).isEmpty()) {
            throw new PlacesException(ErrorCode.CITY_NOT_FOUND);
        }
        String slug = request.citySlug() + "-" + CitySlugs.slugify(request.nameEn());
        if (spotRepository.findBySlug(slug).isPresent()) {
            throw new PlacesException(ErrorCode.SPOT_SLUG_CONFLICT);
        }
        SpotStatus status = request.status() == null ? SpotStatus.PUBLISHED : request.status();
        SpotCategory category = request.category() == null ? SpotCategory.NATURE : request.category();
        Spot spot = Spot.create(UUID.randomUUID(), slug, request.nameZh(), request.nameEn(),
                request.citySlug(), category, normalizeTags(request.tags()),
                request.level(), request.addressEn(), request.addressZh(), request.lat(), request.lng(),
                request.coverImageUrl(), normalizeGallery(request.galleryUrls()),
                request.summaryEn(), request.summaryZh(), request.descriptionEn(), request.descriptionZh(),
                request.openingHours(), request.ticketInfo(), request.visitDuration(),
                request.rating(), request.featured(), request.hiddenGem(), status, now);
        Spot saved = spotRepository.save(spot);
        // 写路径使排行缓存失效：新景点可能进入 / 既有条目内容变化（change: add-spot-ranking-redis-cache）
        rankingCacheService.evictAll();
        return toDetail(saved);
    }

    /** 更新景点（局部替换非空字段）。slug 不可变；不存在 → 404。不触发 viewCount。 */
    public SpotDetail update(String slug, UpdateSpotRequest request, Instant now) {
        Spot spot = spotRepository.findBySlugAndDeletedFalse(slug)
                .orElseThrow(() -> new PlacesException(ErrorCode.SPOT_NOT_FOUND));
        spot.update(request.nameZh(), request.nameEn(), request.category(),
                normalizeTags(request.tags()), request.level(), request.addressEn(), request.addressZh(),
                request.lat(), request.lng(), request.coverImageUrl(), normalizeGallery(request.galleryUrls()),
                request.summaryEn(), request.summaryZh(), request.descriptionEn(), request.descriptionZh(),
                request.openingHours(), request.ticketInfo(), request.visitDuration(),
                request.rating(), request.featured(), request.hiddenGem(), request.status(), now);
        Spot saved = spotRepository.save(spot);
        // 写路径使排行缓存失效：rating/status 等变化可进出榜（change: add-spot-ranking-redis-cache）
        rankingCacheService.evictAll();
        return toDetail(saved);
    }

    private SpotDetail toDetail(Spot spot) {
        List<SpotSummary> nearby = spotRepository
                .findByCitySlugAndStatusAndDeletedFalseAndIdNot(spot.getCitySlug(), SpotStatus.PUBLISHED, spot.getId(),
                        PageRequest.of(0, 6, Sort.by(Sort.Direction.DESC, "viewCount")))
                .stream()
                .map(SpotSummary::from)
                .toList();
        // 相关攻略：post-location-tagging 落地前为空
        return SpotDetail.from(spot, nearby, List.of());
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream()
                .filter(Objects::nonNull)
                .map(t -> t.trim().toLowerCase(Locale.ROOT))
                .filter(t -> !t.isEmpty())
                .distinct()
                .limit(20)
                .toList();
    }

    private List<String> normalizeGallery(List<String> galleryUrls) {
        if (galleryUrls == null || galleryUrls.isEmpty()) {
            return List.of();
        }
        return galleryUrls.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .distinct()
                .limit(20)
                .toList();
    }
}
