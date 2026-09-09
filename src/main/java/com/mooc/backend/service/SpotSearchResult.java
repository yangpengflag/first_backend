package com.mooc.backend.service;

import java.util.List;

/**
 * {@code search_spots} 工具的返回体（change: ai-spot-tools）。
 *
 * <p><b>为什么是精简结构而非复用 {@code SpotSummary}</b>：工具结果直接进入模型上下文，
 * 字段越少越省 token、越不跑题——故只保留"够模型组织回答 + 给出站内链接"的最小集，
 * 不返回画廊图 / 坐标 / 双语全文。
 *
 * <p><b>空态优先</b>：无命中 / 参数非法 / 服务异常一律返回 {@code found=false} 的可读空态
 * （{@code hints} 给出可用取值，让模型能自我纠错），而不是抛异常——异常会变成模型无法转述的堆栈
 * （同 {@code TravelTools} 纪律）。
 *
 * @param found  是否命中
 * @param message 给模型看的可读结论（命中条数 / 未命中原因）
 * @param spots  命中条目（未命中时为空列表）
 * @param hints  未命中或参数非法时的可用取值提示（城市 slug / 分类枚举值等）
 */
public record SpotSearchResult(boolean found, String message, List<SpotHit> spots, List<String> hints) {

    public static SpotSearchResult found(List<SpotHit> spots) {
        return new SpotSearchResult(true, "Found " + spots.size() + " published spot(s).", spots, List.of());
    }

    public static SpotSearchResult empty(String message, List<String> hints) {
        return new SpotSearchResult(false, message, List.of(), hints);
    }

    /** 单条命中（精简视图）。{@code url} 为站内相对链接，供模型在回答中引用来源。 */
    public record SpotHit(String slug,
                          String nameEn,
                          String nameZh,
                          String citySlug,
                          String category,
                          String summary,
                          boolean hiddenGem,
                          Double rating,
                          String url) {
    }
}
