package com.mooc.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 混合搜索响应信封（change: ai-semantic-search，design.md D5）：
 * {@code {items, query, total, request_id}} 平铺结构（snake_case + BaseResponse request_id）。
 * {@code total} 为融合去重并实体化过滤后的最终条目数（非全库计数）；单页无分页。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SearchResponse extends BaseResponse {

    @JsonProperty("items")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private final List<SearchItemResponse> items;

    @JsonProperty("query")
    private final String query;

    @JsonProperty("total")
    private final int total;

    private SearchResponse(List<SearchItemResponse> items, String query) {
        super();
        this.items = items;
        this.query = query;
        this.total = items.size();
    }

    public static SearchResponse of(List<SearchItemResponse> items, String query) {
        return new SearchResponse(items, query);
    }

    public List<SearchItemResponse> getItems() {
        return items;
    }

    public String getQuery() {
        return query;
    }

    public int getTotal() {
        return total;
    }
}
