package com.mooc.backend.places.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.dto.response.BaseResponse;

import java.util.List;

/**
 * 景点列表分页信封（offset 模式）：始终返回 {@code items} / {@code page} / {@code size} / {@code total} / {@code has_more}。
 * 与 {@code PostListResponse.offset} 对齐。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SpotListResponse extends BaseResponse {

    @JsonProperty("items")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private final List<SpotSummary> items;

    @JsonProperty("page") private final int page;
    @JsonProperty("size") private final int size;
    @JsonProperty("total") private final long total;
    @JsonProperty("has_more") private final boolean hasMore;

    private SpotListResponse(List<SpotSummary> items, int page, int size, long total, boolean hasMore) {
        super();
        this.items = items;
        this.page = page;
        this.size = size;
        this.total = total;
        this.hasMore = hasMore;
    }

    public static SpotListResponse of(List<SpotSummary> items, int page, int size, long total) {
        boolean hasMore = (long) page * size < total;
        return new SpotListResponse(items, page, size, total, hasMore);
    }

    public List<SpotSummary> getItems() { return items; }
    public int getPage() { return page; }
    public int getSize() { return size; }
    public long getTotal() { return total; }
    public boolean isHasMore() { return hasMore; }
}
