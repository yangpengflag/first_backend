package com.mooc.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 搜索结果条目（change: ai-semantic-search，design.md D5）。
 *
 * <p>{@code key}：city/spot 为 slug，post 为 uuid（post 无 slug，路由键即 uuid）。
 * {@code url} 为后端组装的详情页站内相对路径，前端不猜路由。{@code score} 为 RRF 分数
 * （仅供调试 / 排序展示，前端不得硬编码阈值）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SearchItemResponse {

    private final String type;
    private final String key;
    private final String title;
    private final String subtitle;
    private final String url;
    private final double score;

    public SearchItemResponse(String type, String key, String title,
                              String subtitle, String url, double score) {
        this.type = type;
        this.key = key;
        this.title = title;
        this.subtitle = subtitle;
        this.url = url;
        this.score = score;
    }

    public String getType() { return type; }
    public String getKey() { return key; }
    public String getTitle() { return title; }
    public String getSubtitle() { return subtitle; }
    public String getUrl() { return url; }
    public double getScore() { return score; }
}
