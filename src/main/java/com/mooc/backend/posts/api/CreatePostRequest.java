package com.mooc.backend.posts.api;

import com.mooc.backend.posts.domain.PostStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建帖子请求（白名单入参）。
 *
 * <p>{@code authorId} 由控制器以 JWT 主体覆盖，<b>不</b>在此接收。
 * {@code status} 缺省由服务层填为 DRAFT；允许直接传 PUBLISHED 发布。
 */
public record CreatePostRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank String content,
        @Pattern(regexp = "^(https?://.+)?$", message = "coverImageUrl must be a valid http(s) URL")
        String coverImageUrl,
        @Size(max = 10) List<@Size(max = 30) String> tags,
        PostStatus status,
        /** 单城市语境地点关联（city slug），可选。 */
        String cityId,
        /** 多 POI 关联（Spot slug 数组），可选，缺省空；写入 post_spots 关联表。 */
        @Size(max = 20) List<@Size(max = 80) String> spotSlugs
) {
}
