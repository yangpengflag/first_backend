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
        PostStatus status
) {
}
