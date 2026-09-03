package com.mooc.backend.places.service;

import com.mooc.backend.auth.domain.Role;
import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.auth.domain.UserStatus;
import com.mooc.backend.comments.api.CreateCommentRequest;
import com.mooc.backend.comments.exception.CommentException;
import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.places.api.SpotCommentResponse;
import com.mooc.backend.places.domain.Spot;
import com.mooc.backend.places.domain.SpotComment;
import com.mooc.backend.places.repository.SpotCommentRepository;
import com.mooc.backend.places.repository.SpotRepository;

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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 景点评论业务逻辑（镜像 comments.CommentService，postId → spotSlug）。
 *
 * <p>两层模型：顶层评论（parentCommentId = null）+ 其回复。回复的父评论必须指向同景点的
 * 顶层评论（否则 {@code INVALID_PARENT_COMMENT}）。作者展示信息批量 IN 解析
 * （{@code UserRepository.findAllById}），缺失 / 已软删回退占位，避免 N+1 与隐私泄露。
 * 景点不存在统一抛 {@code SPOT_NOT_FOUND}；评论相关错误复用 {@code CommentException} 与既有错误码。
 */
@Service
public class SpotCommentService {

    private static final Logger log = LoggerFactory.getLogger(SpotCommentService.class);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final SpotCommentRepository spotCommentRepository;
    private final SpotRepository spotRepository;
    private final UserRepository userRepository;

    public SpotCommentService(SpotCommentRepository spotCommentRepository, SpotRepository spotRepository,
                              UserRepository userRepository) {
        this.spotCommentRepository = spotCommentRepository;
        this.spotRepository = spotRepository;
        this.userRepository = userRepository;
    }

    /** 发布评论：顶层或回复；校验景点存在、回复父评论同景点且为顶层评论。 */
    public SpotCommentResponse create(String spotSlug, UUID userId, CreateCommentRequest request, Instant now) {
        if (spotRepository.findBySlugAndDeletedFalse(spotSlug).isEmpty()) {
            throw new CommentException(ErrorCode.SPOT_NOT_FOUND);
        }
        UUID parentId = request.parentCommentId();
        if (parentId != null) {
            SpotComment parent = spotCommentRepository.findBySpotSlugAndIdAndDeletedFalse(spotSlug, parentId)
                    .orElseThrow(() -> new CommentException(ErrorCode.INVALID_PARENT_COMMENT));
            if (!parent.isTopLevel()) {
                throw new CommentException(ErrorCode.INVALID_PARENT_COMMENT);
            }
        }
        SpotComment comment = SpotComment.create(spotSlug, userId, request.content(), parentId, now);
        SpotComment saved = spotCommentRepository.save(comment);
        AuthorView author = resolveAuthor(userId);
        return SpotCommentResponse.from(saved, author.name(), author.avatarUrl(), 0L);
    }

    /** 顶层评论分页（倒序），含 reply_count 与作者信息。景点不存在 → SPOT_NOT_FOUND。 */
    public Page<SpotCommentResponse> listTopLevel(String spotSlug, int page, int size, Instant now) {
        if (spotRepository.findBySlugAndDeletedFalse(spotSlug).isEmpty()) {
            throw new CommentException(ErrorCode.SPOT_NOT_FOUND);
        }
        Pageable pageable = buildPageable(page, clampSize(size), Sort.Direction.DESC);
        Page<SpotComment> commentPage = spotCommentRepository.findBySpotSlugAndParentCommentIdIsNullAndDeletedFalse(spotSlug, pageable);
        return toResponsePage(commentPage, pageable);
    }

    /** 回复分页（升序）。父评论不存在或已删 → COMMENT_NOT_FOUND。 */
    public Page<SpotCommentResponse> listReplies(UUID commentId, int page, int size, Instant now) {
        if (spotCommentRepository.findByIdAndDeletedFalse(commentId).isEmpty()) {
            throw new CommentException(ErrorCode.COMMENT_NOT_FOUND);
        }
        Pageable pageable = buildPageable(page, clampSize(size), Sort.Direction.ASC);
        Page<SpotComment> replyPage = spotCommentRepository.findByParentCommentIdAndDeletedFalse(commentId, pageable);
        return toResponsePage(replyPage, pageable);
    }

    /**
     * 删除：仅作者本人；非作者 → NOT_COMMENT_AUTHOR；不存在 / 已软删 → COMMENT_NOT_FOUND。
     * 删除顶层评论时级联软删其全部回复（避免孤儿）；删除回复（叶子）不级联。
     */
    public void delete(UUID commentId, UUID userId, Instant now) {
        SpotComment comment = spotCommentRepository.findByIdAndDeletedFalse(commentId)
                .orElseThrow(() -> new CommentException(ErrorCode.COMMENT_NOT_FOUND));
        // 作者本人或管理员（ADMIN）可删；其余非作者 → NOT_COMMENT_AUTHOR。
        boolean isAdmin = userRepository.findById(userId)
                .map(u -> u.getRole() == Role.ADMIN)
                .orElse(false);
        if (!comment.getUserId().equals(userId) && !isAdmin) {
            throw new CommentException(ErrorCode.NOT_COMMENT_AUTHOR);
        }
        if (comment.isTopLevel()) {
            List<SpotComment> replies = spotCommentRepository.findAllByParentCommentIdAndDeletedFalse(commentId);
            for (SpotComment reply : replies) {
                reply.softDelete(now);
            }
            spotCommentRepository.saveAll(replies);
        }
        comment.softDelete(now);
        spotCommentRepository.save(comment);
    }

    // ---------- 内部辅助 ----------

    private Page<SpotCommentResponse> toResponsePage(Page<SpotComment> commentPage, Pageable pageable) {
        List<UUID> authorIds = commentPage.getContent().stream().map(SpotComment::getUserId).distinct().toList();
        Map<UUID, AuthorView> authors = resolveAuthors(authorIds);
        List<SpotCommentResponse> items = commentPage.getContent().stream()
                .map(c -> SpotCommentResponse.from(c,
                        authors.getOrDefault(c.getUserId(), AuthorView.UNKNOWN).name(),
                        authors.getOrDefault(c.getUserId(), AuthorView.UNKNOWN).avatarUrl(),
                        spotCommentRepository.countByParentCommentIdAndDeletedFalse(c.getId())))
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
