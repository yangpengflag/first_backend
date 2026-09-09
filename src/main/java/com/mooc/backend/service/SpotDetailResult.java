package com.mooc.backend.service;

import java.util.List;

/**
 * {@code get_spot_details} 工具的返回体（change: ai-spot-tools）。
 *
 * <p>承担 RAG 片段最容易漏召回的<b>结构化字段</b>（开放时间 / 门票 / 建议时长 / 地址），
 * 单条返回；未收录或非 PUBLISHED → {@code found=false} 的可读空态（不抛异常）。
 *
 * @param found 是否命中（未命中时除 message / hints 外字段均为 null）
 * @param message 给模型看的可读结论
 * @param hints 未命中时的提示（如"可用 slug 形如 {city}-{spot}"）
 */
public record SpotDetailResult(boolean found,
                               String message,
                               String slug,
                               String nameEn,
                               String nameZh,
                               String summaryEn,
                               String openingHours,
                               String ticketInfo,
                               String visitDuration,
                               String addressEn,
                               List<String> tags,
                               String url,
                               List<String> hints) {

    public static SpotDetailResult from(com.mooc.backend.dto.response.SpotDetail detail) {
        List<String> tags = detail.getTags() == null ? List.of() : List.copyOf(detail.getTags());
        return new SpotDetailResult(true,
                "Details for " + detail.getNameEn() + ".",
                detail.getSlug(), detail.getNameEn(), detail.getNameZh(), detail.getSummaryEn(),
                detail.getOpeningHours(), detail.getTicketInfo(), detail.getVisitDuration(),
                detail.getAddressEn(), tags, "/spots/" + detail.getSlug(), List.of());
    }

    public static SpotDetailResult notFound(String slug, List<String> hints) {
        return new SpotDetailResult(false,
                "No published spot with slug '" + slug + "' on WanderChina.",
                null, null, null, null, null, null, null, null, List.of(), null, hints);
    }

    public static SpotDetailResult unavailable(String slug) {
        return new SpotDetailResult(false,
                "Spot details are temporarily unavailable.",
                null, null, null, null, null, null, null, null, List.of(), null, List.of());
    }
}
