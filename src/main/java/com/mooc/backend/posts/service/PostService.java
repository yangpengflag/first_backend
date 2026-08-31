package com.mooc.backend.posts.service;

import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.auth.domain.UserStatus;
import com.mooc.backend.posts.MarkdownSummary;
import com.mooc.backend.posts.api.CreatePostRequest;
import com.mooc.backend.posts.api.PostListResponse;
import com.mooc.backend.posts.api.PostResponse;
import com.mooc.backend.posts.api.PostStatsView;
import com.mooc.backend.posts.api.PostSummary;
import com.mooc.backend.posts.api.UpdatePostRequest;
import com.mooc.backend.posts.domain.Post;
import com.mooc.backend.posts.domain.PostStatus;
import com.mooc.backend.posts.exception.PostException;
import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.posts.repository.PostRepository;
import com.mooc.backend.posts.repository.PostSort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Base64;
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
 *
 * <p>列表读取（公开 / 我的）通过 {@code PostRepository} 的聚合查询实时取得互动统计，
 * 由 {@code PostListResponse} 统一信封返回（cursor 模式用于 latest 排序，offset 模式用于 top / most_commented）。
 */
@Service
public class PostService {

    private static final Logger log = LoggerFactory.getLogger(PostService.class);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

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

    /** 公开列表：仅 PUBLISHED，支持 latest（cursor）/ top / most_commented（offset）排序与混合分页。 */
    public PostListResponse listPublished(String sortParam, String cursor, Integer page, Integer size, Instant now) {
        int safeSize = clampSize(size);
        PostSort sort = PostSort.from(sortParam);

        if (sort == PostSort.LATEST && cursor != null) {
            Cursor c = decodeCursor(cursor);
            List<PostStatsView> stats = postRepository.findPublishedStats(sort, safeSize + 1, 0, c.ts(), c.id(), true);
            boolean hasMore = stats.size() > safeSize;
            List<PostStatsView> pageStats = hasMore ? stats.subList(0, safeSize) : stats;
            Map<UUID, Post> byId = fetchPosts(pageStats);
            List<PostSummary> items = toSummaries(pageStats, byId);
            String next = hasMore ? encodeCursor(pageStats.get(pageStats.size() - 1), byId) : null;
            return PostListResponse.cursor(items, next, hasMore);
        }

        int safePage = (page == null || page < 1) ? 1 : page;
        int offset = (safePage - 1) * safeSize;
        List<PostStatsView> stats = postRepository.findPublishedStats(sort, safeSize, offset, null, null, false);
        long total = postRepository.countByStatusAndDeletedFalse(PostStatus.PUBLISHED);
        Map<UUID, Post> byId = fetchPosts(stats);
        List<PostSummary> items = toSummaries(stats, byId);
        return PostListResponse.offset(items, safePage, safeSize, total);
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

    /** 我的帖子：当前用户全部状态（含 DRAFT），软删已被查询层 AndDeletedFalse 排除；同步带互动统计。 */
    public PostListResponse listMine(UUID authorId, String sortParam, String cursor, Integer page, Integer size, Instant now) {
        int safeSize = clampSize(size);
        PostSort sort = PostSort.from(sortParam);

        if (sort == PostSort.LATEST && cursor != null) {
            Cursor c = decodeCursor(cursor);
            List<PostStatsView> stats = postRepository.findMyStats(authorId, sort, safeSize + 1, 0, c.ts(), c.id(), true);
            boolean hasMore = stats.size() > safeSize;
            List<PostStatsView> pageStats = hasMore ? stats.subList(0, safeSize) : stats;
            Map<UUID, Post> byId = fetchPosts(pageStats);
            List<PostSummary> items = toSummaries(pageStats, byId);
            String next = hasMore ? encodeCursor(pageStats.get(pageStats.size() - 1), byId) : null;
            return PostListResponse.cursor(items, next, hasMore);
        }

        int safePage = (page == null || page < 1) ? 1 : page;
        int offset = (safePage - 1) * safeSize;
        List<PostStatsView> stats = postRepository.findMyStats(authorId, sort, safeSize, offset, null, null, false);
        long total = postRepository.countByAuthorIdAndDeletedFalse(authorId);
        Map<UUID, Post> byId = fetchPosts(stats);
        List<PostSummary> items = toSummaries(stats, byId);
        return PostListResponse.offset(items, safePage, safeSize, total);
    }

    // ---------- 内部辅助 ----------

    private int clampSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private Map<UUID, Post> fetchPosts(List<PostStatsView> stats) {
        if (stats.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = stats.stream().map(PostStatsView::postId).toList();
        return postRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Post::getId, p -> p, (a, b) -> a));
    }

    private List<PostSummary> toSummaries(List<PostStatsView> stats, Map<UUID, Post> byId) {
        return stats.stream()
                .map(s -> {
                    Post p = byId.get(s.postId());
                    if (p == null) {
                        return null;
                    }
                    AuthorView a = resolveAuthor(p.getAuthorId());
                    return PostSummary.from(p, a.name(), a.avatarUrl(), MarkdownSummary.derive(p.getContent()),
                            s.commentCount(), s.upVoteCount(), s.bookmarkCount());
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private String encodeCursor(PostStatsView last, Map<UUID, Post> byId) {
        Post p = byId.get(last.postId());
        if (p == null) {
            return null;
        }
        return encodeCursor(p.getCreatedAt(), p.getId());
    }

    private String encodeCursor(Instant ts, UUID id) {
        return B64.encodeToString((ts.toString() + "|" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String token) {
        String s = new String(B64D.decode(token), java.nio.charset.StandardCharsets.UTF_8);
        int idx = s.indexOf('|');
        Instant ts = Instant.parse(s.substring(0, idx));
        UUID id = UUID.fromString(s.substring(idx + 1));
        return new Cursor(ts, id);
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

    /** 游标：编码为 base64(createdAtISO + "|" + id)，按 (created_at, id) 截断，无服务端状态。 */
    private record Cursor(Instant ts, UUID id) {
    }

    /** 作者展示信息视图；占位用于作者缺失 / 已注销。 */
    private record AuthorView(String name, String avatarUrl) {
        private static final AuthorView UNKNOWN = new AuthorView("[unknown user]", null);
    }
}
