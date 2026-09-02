package com.mooc.backend.places.api;

import com.mooc.backend.places.domain.SpotCategory;
import com.mooc.backend.places.domain.SpotStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 景点创建请求（写 API POST /api/spots）。
 *
 * <p>nameEn / nameZh / citySlug 必填；其余可选。slug 由服务层推导
 * {@code {citySlug}-{slugify(nameEn)}}，冲突 → 409 SPOT_SLUG_CONFLICT。
 * status 缺省 PUBLISHED。creator 不持久化（仅据 JWT 鉴权）。
 * summary（摘要）/ description（详述）分开提供。
 */
public record CreateSpotRequest(
        @NotBlank @Size(max = 200) String nameEn,
        @NotBlank @Size(max = 200) String nameZh,
        String descriptionEn,
        String descriptionZh,
        String summaryEn,
        String summaryZh,
        @Pattern(regexp = "^(https?://|/).*", message = "coverImageUrl 须为 URL")
        String coverImageUrl,
        @Size(max = 20) List<@Pattern(regexp = "^(https?://|/).*", message = "gallery URL 须为 URL") String> galleryUrls,
        @Size(max = 20) List<@Size(max = 40) String> tags,
        @NotBlank String citySlug,
        SpotCategory category,
        SpotStatus status,
        String level,
        String addressEn,
        String addressZh,
        Double lat,
        Double lng,
        String openingHours,
        String ticketInfo,
        String visitDuration,
        Double rating,
        boolean featured,
        boolean hiddenGem) {
}
