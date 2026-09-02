package com.mooc.backend.places.api;

import com.mooc.backend.places.domain.SpotCategory;
import com.mooc.backend.places.domain.SpotStatus;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 景点更新请求（写 API PUT /api/spots/{slug}）。补丁式：null 保留原值。
 *
 * <p>slug 创建后不可变（更新不重算、不接收 citySlug）；citySlug 随 slug 绑定亦不可变。
 * featured / hiddenGem 用 Boolean 包装，null 保留原值。summary / description 分开。
 */
public record UpdateSpotRequest(
        String nameEn,
        String nameZh,
        String descriptionEn,
        String descriptionZh,
        String summaryEn,
        String summaryZh,
        @Pattern(regexp = "^(https?://|/).*", message = "coverImageUrl 须为 URL")
        String coverImageUrl,
        @Size(max = 20) List<@Pattern(regexp = "^(https?://|/).*", message = "gallery URL 须为 URL") String> galleryUrls,
        @Size(max = 20) List<@Size(max = 40) String> tags,
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
        Boolean featured,
        Boolean hiddenGem) {
}
