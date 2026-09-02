package com.mooc.backend.posts.api;

import com.mooc.backend.posts.domain.PostStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 更新帖子请求（补丁语义：仅非空字段生效，其余保持不变）。
 *
 * <p>用于「作者发布草稿」场景——仅传 {@code {"status":"PUBLISHED"}} 即可，其余字段留空。
 */
public record UpdatePostRequest(
        @Size(max = 200) String title,
        String content,
        @Pattern(regexp = "^(https?://.+)?$", message = "coverImageUrl must be a valid http(s) URL")
        String coverImageUrl,
        @Size(max = 10) List<@Size(max = 30) String> tags,
        PostStatus status,
        /** 单城市语境地点关联（city slug），可选；补丁式，null 保留原值。 */
        String cityId,
        /** 多 POI 关联（Spot slug 数组），可选；补丁式，null 保留原值；写入 post_spots 关联表。 */
        @Size(max = 20) List<@Size(max = 80) String> spotSlugs
) {
}
