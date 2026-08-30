package com.mooc.backend.comments.service;

import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.auth.domain.UserStatus;
import com.mooc.backend.comments.api.CommentResponse;
import com.mooc.backend.comments.api.CreateCommentRequest;
import com.mooc.backend.comments.domain.Comment;
import com.mooc.backend.comments.exception.CommentException;
import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.comments.repository.CommentRepository;
import com.mooc.backend.posts.repository.PostRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 评论业务逻辑。
 *
 * <p>两层模型：顶层评论（parentCommentId = null）+ 其回复。回复的父评论必须指向同帖的
 * 顶层评论（否则 {@code INVALID_PARENT_COMMENT}）。作者展示信息批量 IN 解析
 * （{@code UserRepository.findAllById}），缺失 / 已软删回退占位，避免 N+1 与隐私泄露。
 */
@Service
public class CommentService {

    private static final Logger log = LoggerFactory.getLogger(CommentService.class);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final CommentRepository commentRepository;
    private final PostRepository postRepository;
    private final UserRepository userRepository;

    public CommentService(CommentRepository commentRepository, PostRepository postRepository,
                          UserRepository userRepository) {
        this.commentRepository = commentRepository;
        this.postRepository = postRepository;
        this.userRepository = userRepository;
    }

    /** 发布评论：顶层或回复；校验帖存在、回复父评论同帖且为顶层评论。 */
    public CommentResponse create(UUID postId, UUID userId, CreateCommentRequest request, Instant now) {
        if (postRepository.findByIdAndDeletedFalse(postId).isEmpty()) {
            throw new CommentException(ErrorCode.POST_NOT_FOUND);
        }
        UUID parentId = request.parentCommentId();
        if (parentId != null) {
            Comment parent = commentRepository.findByPostIdAndIdAndDeletedFalse(postId, parentId)
                    .orElseThrow(() -> new CommentException(ErrorCode.INVALID_PARENT_COMMENT));
            if (!parent.isTopLevel()) {
                throw new CommentException(ErrorCode.INVALID_PARENT_COMMENT);
            }
        }
        Comment comment = Comment.create(postId, userId, request.content(), parentId, now);
        Comment saved = commentRepository.save(comment);
        AuthorView author = resolveAuthor(userId);
        return CommentResponse.from(saved, author.name(), author.avatarUrl(), 0L);
    }

    /** 顶层评论分页（倒序），含 reply_count 与作者信息。帖不存在 → POST_NOT_FOUND。 */
    public Page<CommentResponse> listTopLevel(UUID postId, int page, int size, Instant now) {
        if (postRepository.findByIdAndDeletedFalse(postId).isEmpty()) {
            throw new CommentException(ErrorCode.POST_NOT_FOUND);
        }
        Pageable pageable = buildPageable(page, clampSize(size), Sort.Direction.DESC);
        Page<Comment> commentPage = commentRepository.findByPostIdAndParentCommentIdIsNullAndDeletedFalse(postId, pageable);
        return toResponsePage(commentPage, pageable);
    }

    /** 回复分页（升序）。父评论不存在或已删 → COMMENT_NOT_FOUND。 */
    public Page<CommentResponse> listReplies(UUID commentId, int page, int size, Instant now) {
        if (commentRepository.findByIdAndDeletedFalse(commentId).isEmpty()) {
            throw new CommentException(ErrorCode.COMMENT_NOT_FOUND);
        }
        Pageable pageable = buildPageable(page, clampSize(size), Sort.Direction.ASC);
        Page<Comment> replyPage = commentRepository.findByParentCommentIdAndDeletedFalse(commentId, pageable);
        return toResponsePage(replyPage, pageable);
    }

    /**
     * 删除：仅作者本人；非作者 → NOT_COMMENT_AUTHOR；不存在 / 已软删 → COMMENT_NOT_FOUND。
     * 删除顶层评论时级联软删其全部回复（避免孤儿）；删除回复（叶子）不级联。
     */
    public void delete(UUID commentId, UUID userId, Instant now) {
        Comment comment = commentRepository.findByIdAndDeletedFalse(commentId)
                .orElseThrow(() -> new CommentException(ErrorCode.COMMENT_NOT_FOUND));
        if (!comment.getUserId().equals(userId)) {
            throw new CommentException(ErrorCode.NOT_COMMENT_AUTHOR);
        }
        if (comment.isTopLevel()) {
            List<Comment> replies = commentRepository.findAllByParentCommentIdAndDeletedFalse(commentId);
            for (Comment reply : replies) {
                reply.softDelete(now);
            }
            commentRepository.saveAll(replies);
        }
        comment.softDelete(now);
        commentRepository.save(comment);
    }

    // ---------- 内部辅助 ----------

    private Page<CommentResponse> toResponsePage(Page<Comment> commentPage, Pageable pageable) {
        List<UUID> authorIds = commentPage.getContent().stream().map(Comment::getUserId).distinct().toList();
        Map<UUID, AuthorView> authors = resolveAuthors(authorIds);
        List<CommentResponse> items = commentPage.getContent().stream()
                .map(c -> CommentResponse.from(c,
                        authors.getOrDefault(c.getUserId(), AuthorView.UNKNOWN).name(),
                        authors.getOrDefault(c.getUserId(), AuthorView.UNKNOWN).avatarUrl(),
                        commentRepository.countByParentCommentIdAndDeletedFalse(c.getId())))
                .toList();
        return new PageImpl<>(items, pageable, commentPage.getTotalElements());
    }

    private int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private Pageable buildPageable(int page, int size, Sort.Direction direction) {
        int safePage = Math.max(page, 0);
        return PageRequest.of(safePage, size, Sort.by(direction, "createdAt"));
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
