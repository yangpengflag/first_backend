package com.mooc.backend.posts.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.dto.response.BaseResponse;

import java.util.List;

/**
 * 帖子列表统一分页信封。
 *
 * <p>始终返回 {@code items} / {@code next_cursor} / {@code has_more}；
 * offset 模式（sort=top / most_commented）额外返回 {@code page} / {@code size} / {@code total}。
 * 与 {@code api-conventions} 分页响应格式对齐（cursor 模式不输出 page/size/total）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PostListResponse extends BaseResponse {

    @JsonProperty("items")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private final List<PostSummary> items;

    @JsonProperty("next_cursor")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private final String nextCursor;

    @JsonProperty("has_more")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private final boolean hasMore;

    @JsonProperty("page")
    private final Integer page;

    @JsonProperty("size")
    private final Integer size;

    @JsonProperty("total")
    private final Long total;

    private PostListResponse(List<PostSummary> items, String nextCursor, boolean hasMore,
                             Integer page, Integer size, Long total) {
        super();
        this.items = items;
        this.nextCursor = nextCursor;
        this.hasMore = hasMore;
        this.page = page;
        this.size = size;
        this.total = total;
    }

    /** cursor 模式（sort=latest）：仅 items / next_cursor / has_more。 */
    public static PostListResponse cursor(List<PostSummary> items, String nextCursor, boolean hasMore) {
        return new PostListResponse(items, nextCursor, hasMore, null, null, null);
    }

    /** offset 模式（sort=top / most_commented）：额外 page / size / total。 */
    public static PostListResponse offset(List<PostSummary> items, int page, int size, long total) {
        boolean hasMore = (long) page * size < total;
        return new PostListResponse(items, null, hasMore, page, size, total);
    }

    public List<PostSummary> getItems() {
        return items;
    }

    public String getNextCursor() {
        return nextCursor;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public Integer getPage() {
        return page;
    }

    public Integer getSize() {
        return size;
    }

    public Long getTotal() {
        return total;
    }
}
