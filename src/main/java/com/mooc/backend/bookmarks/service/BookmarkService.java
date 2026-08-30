package com.mooc.backend.bookmarks.service;

import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.auth.domain.UserStatus;
import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.bookmarks.api.BookmarkResponse;
import com.mooc.backend.bookmarks.api.BookmarkSummary;
import com.mooc.backend.bookmarks.domain.Bookmark;
import com.mooc.backend.bookmarks.exception.BookmarkException;
import com.mooc.backend.bookmarks.repository.BookmarkRepository;
import com.mooc.backend.posts.api.PostSummary;
import com.mooc.backend.posts.domain.Post;
import com.mooc.backend.posts.domain.PostStatus;
import com.mooc.backend.posts.MarkdownSummary;
import com.mooc.backend.posts.repository.PostRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 收藏业务逻辑。
 *
 * <p>切换收藏（一人一帖唯一，取消走物理删除）。列表全量返回用户收藏项：失效帖子以
 * {@code available=false} + {@code post=null} 呈现（不静默跳过），以消弭分页空档。
 * 作者信息批量 IN 解析，缺失 / 已软删回退占位。
 */
@Service
public class BookmarkService {

    private static final Logger log = LoggerFactory.getLogger(BookmarkService.class);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final BookmarkRepository bookmarkRepository;
    private final PostRepository postRepository;
    private final UserRepository userRepository;

    public BookmarkService(BookmarkRepository bookmarkRepository, PostRepository postRepository,
                           UserRepository userRepository) {
        this.bookmarkRepository = bookmarkRepository;
        this.postRepository = postRepository;
        this.userRepository = userRepository;
    }

    /** 切换收藏；返回切换后是否已收藏。 */
    @Transactional
    public BookmarkResponse toggle(UUID postId, UUID userId, Instant now) {
        if (postRepository.findByIdAndDeletedFalse(postId).isEmpty()) {
            throw new BookmarkException(ErrorCode.POST_NOT_FOUND);
        }
        return bookmarkRepository.findByPostIdAndUserId(postId, userId)
                .map(existing -> {
                    bookmarkRepository.delete(existing);
                    return BookmarkResponse.from(postId, false);
                })
                .orElseGet(() -> {
                    Bookmark bookmark = Bookmark.create(postId, userId, now);
                    bookmarkRepository.save(bookmark);
                    return BookmarkResponse.from(postId, true);
                });
    }

    /** 查询某用户是否已收藏该帖（帖子不存在抛 {@code POST_NOT_FOUND}）。 */
    public boolean isBookmarked(UUID postId, UUID userId) {
        if (postRepository.findByIdAndDeletedFalse(postId).isEmpty()) {
            throw new BookmarkException(ErrorCode.POST_NOT_FOUND);
        }
        return bookmarkRepository.findByPostIdAndUserId(postId, userId).isPresent();
    }

    /** 我的收藏列表（全量，含失效占位）。 */
    public Page<BookmarkSummary> listBookmarks(UUID userId, int page, int size, Instant now) {
        int safeSize = clampSize(size);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Bookmark> bookmarkPage = bookmarkRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);

        List<UUID> postIds = bookmarkPage.getContent().stream().map(Bookmark::getPostId).toList();
        Map<UUID, Post> postsById = postRepository.findAllById(postIds).stream()
                .collect(Collectors.toMap(Post::getId, p -> p, (a, b) -> a));

        List<UUID> authorIds = postsById.values().stream()
                .filter(BookmarkService::isAvailable)
                .map(Post::getAuthorId)
                .distinct()
                .toList();
        Map<UUID, AuthorView> authors = resolveAuthors(authorIds);

        List<BookmarkSummary> items = bookmarkPage.getContent().stream()
                .map(b -> {
                    Post post = postsById.get(b.getPostId());
                    boolean available = isAvailable(post);
                    PostSummary summary = available
                            ? PostSummary.from(post,
                                authors.getOrDefault(post.getAuthorId(), AuthorView.UNKNOWN).name(),
                                authors.getOrDefault(post.getAuthorId(), AuthorView.UNKNOWN).avatarUrl(),
                                MarkdownSummary.derive(post.getContent()))
                            : null;
                    return new BookmarkSummary(b.getPostId(), available, summary);
                })
                .toList();

        return new PageImpl<>(items, pageable, bookmarkPage.getTotalElements());
    }

    private static boolean isAvailable(Post post) {
        return post != null && !post.isDeleted() && post.getStatus() == PostStatus.PUBLISHED;
    }

    private int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

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

    private record AuthorView(String name, String avatarUrl) {
        private static final AuthorView UNKNOWN = new AuthorView("[unknown user]", null);
    }
}
