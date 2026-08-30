package com.mooc.backend.posts.service;

import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.auth.domain.UserStatus;
import com.mooc.backend.posts.MarkdownSummary;
import com.mooc.backend.posts.api.CreatePostRequest;
import com.mooc.backend.posts.api.PostResponse;
import com.mooc.backend.posts.api.PostSummary;
import com.mooc.backend.posts.api.UpdatePostRequest;
import com.mooc.backend.posts.domain.Post;
import com.mooc.backend.posts.domain.PostStatus;
import com.mooc.backend.posts.exception.PostException;
import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.posts.repository.PostRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 帖子业务逻辑。
 *
 * <p>依赖 {@code UserRepository} 仅用于只读解析作者展示信息（displayName / avatarUrl），
 * 通过批量 IN 查询避免 N+1；不持有 User 实体的写权限。
 */
@Service
public class PostService {

    private static final Logger log = LoggerFactory.getLogger(PostService.class);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final PostRepository postRepository;
    private final UserRepository userRepository;

    public PostService(PostRepository postRepository, UserRepository userRepository) {
        this.postRepository = postRepository;
        this.userRepository = userRepository;
    }

    /** 创建帖子：authorId 取自 JWT 主体（控制器已覆盖），tag 归一化后落库。 */
    public PostResponse create(UUID authorId, CreatePostRequest request, Instant now) {
        PostStatus status = request.status() == null ? PostStatus.DRAFT : request.status();
        List<String> tags = normalizeTags(request.tags());
        Post post = Post.create(authorId, request.title(), request.content(),
                request.coverImageUrl(), tags, status, now);
        Post saved = postRepository.save(post);
        AuthorView author = resolveAuthor(authorId);
        return PostResponse.from(saved, author.name(), author.avatarUrl(),
                MarkdownSummary.derive(saved.getContent()));
    }

    /** 公开列表：仅 PUBLISHED，按 created_at 倒序，size 钳制上限；作者信息批量解析。 */
    public Page<PostSummary> listPublished(int page, int size, Instant now) {
        int safeSize = clampSize(size);
        Pageable pageable = buildPageable(page, safeSize);
        Page<Post> postPage = postRepository.findByStatusAndDeletedFalse(PostStatus.PUBLISHED, pageable);
        Map<UUID, AuthorView> authors = resolveAuthors(
                postPage.getContent().stream().map(Post::getAuthorId).distinct().toList());
        List<PostSummary> items = postPage.getContent().stream()
                .map(p -> PostSummary.from(p, authors.getOrDefault(p.getAuthorId(), AuthorView.UNKNOWN).name(),
                        authors.getOrDefault(p.getAuthorId(), AuthorView.UNKNOWN).avatarUrl(),
                        MarkdownSummary.derive(p.getContent())))
                .toList();
        return new PageImpl<>(items, pageable, postPage.getTotalElements());
    }

    /** 公开详情：非 PUBLISHED 或已软删（查询层 AndDeletedFalse 过滤）一律 404。 */
    public PostResponse getPublished(UUID id, Instant now) {
        Post post = postRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new PostException(ErrorCode.POST_NOT_FOUND));
        if (post.getStatus() != PostStatus.PUBLISHED) {
            throw new PostException(ErrorCode.POST_NOT_FOUND);
        }
        AuthorView author = resolveAuthor(post.getAuthorId());
        return PostResponse.from(post, author.name(), author.avatarUrl(),
                MarkdownSummary.derive(post.getContent()));
    }

    /** 编辑：仅作者本人；补丁式更新非空字段。 */
    public PostResponse update(UUID id, UUID authorId, UpdatePostRequest request, Instant now) {
        Post post = postRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new PostException(ErrorCode.POST_NOT_FOUND));
        if (!post.getAuthorId().equals(authorId)) {
            throw new PostException(ErrorCode.NOT_POST_AUTHOR);
        }
        String title = request.title() != null ? request.title() : post.getTitle();
        String content = request.content() != null ? request.content() : post.getContent();
        String cover = request.coverImageUrl() != null ? request.coverImageUrl() : post.getCoverImageUrl();
        List<String> tags = request.tags() != null ? normalizeTags(request.tags()) : post.getTags();
        PostStatus status = request.status() != null ? request.status() : post.getStatus();
        post.update(title, content, cover, tags, status, now);
        Post saved = postRepository.save(post);
        AuthorView author = resolveAuthor(saved.getAuthorId());
        return PostResponse.from(saved, author.name(), author.avatarUrl(),
                MarkdownSummary.derive(saved.getContent()));
    }

    /** 删除（软删除）：仅作者本人；非作者 403，不存在 404，成功 204。 */
    public void delete(UUID id, UUID authorId, Instant now) {
        Post post = postRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new PostException(ErrorCode.POST_NOT_FOUND));
        if (!post.getAuthorId().equals(authorId)) {
            throw new PostException(ErrorCode.NOT_POST_AUTHOR);
        }
        post.softDelete(now);
        postRepository.save(post);
    }

    /** 我的帖子：当前用户全部状态（含 DRAFT），软删已被查询层 AndDeletedFalse 排除。 */
    public Page<PostSummary> listMine(UUID authorId, int page, int size, Instant now) {
        int safeSize = clampSize(size);
        Pageable pageable = buildPageable(page, safeSize);
        Page<Post> postPage = postRepository.findByAuthorIdAndDeletedFalse(authorId, pageable);
        AuthorView author = resolveAuthor(authorId);
        List<PostSummary> items = postPage.getContent().stream()
                .map(p -> PostSummary.from(p, author.name(), author.avatarUrl(),
                        MarkdownSummary.derive(p.getContent())))
                .toList();
        return new PageImpl<>(items, pageable, postPage.getTotalElements());
    }

    // ---------- 内部辅助 ----------

    private int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private Pageable buildPageable(int page, int size) {
        int safePage = Math.max(page, 0);
        return PageRequest.of(safePage, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    /** tag 归一化：trim + 小写 + 去空 + 去重 + 上限 10（与入参校验双保险）。 */
    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream()
                .filter(Objects::nonNull)
                .map(t -> t.trim().toLowerCase(Locale.ROOT))
                .filter(t -> !t.isEmpty())
                .distinct()
                .limit(10)
                .toList();
    }

    private AuthorView resolveAuthor(UUID authorId) {
        return resolveAuthors(List.of(authorId)).getOrDefault(authorId, AuthorView.UNKNOWN);
    }

    /** 批量解析作者展示信息；作者不存在或已软删则回退占位（不泄露隐私）。 */
    private Map<UUID, AuthorView> resolveAuthors(List<UUID> authorIds) {
        Map<UUID, AuthorView> result = new HashMap<>();
        if (authorIds.isEmpty()) {
            return result;
        }
        List<User> users = userRepository.findAllById(authorIds);
        Map<UUID, User> byId = users.stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        for (UUID id : authorIds) {
            User user = byId.get(id);
            if (user == null || user.getStatus() == UserStatus.DELETED) {
                result.put(id, AuthorView.UNKNOWN);
            } else {
                result.put(id, new AuthorView(user.getDisplayName(), user.getAvatarUrl()));
            }
        }
        return result;
    }

    /** 作者展示信息视图；占位用于作者缺失 / 已注销。 */
    private record AuthorView(String name, String avatarUrl) {
        private static final AuthorView UNKNOWN = new AuthorView("[unknown user]", null);
    }
}
