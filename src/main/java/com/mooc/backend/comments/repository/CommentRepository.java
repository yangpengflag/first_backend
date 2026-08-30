package com.mooc.backend.comments.repository;

import com.mooc.backend.comments.domain.Comment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 评论仓储。
 *
 * <p>软删除经查询层显式 {@code AndDeletedFalse} 过滤（不在实体上施加全局过滤），
 * 与 {@code posts} / {@code users} 约定一致。
 */
public interface CommentRepository extends JpaRepository<Comment, UUID> {

    /** 某帖子的顶层评论（parentCommentId 为 null），显式排除软删行。 */
    Page<Comment> findByPostIdAndParentCommentIdIsNullAndDeletedFalse(UUID postId, Pageable pageable);

    /** 某父评论的回复，显式排除软删行。 */
    Page<Comment> findByParentCommentIdAndDeletedFalse(UUID parentId, Pageable pageable);

    /** 按 id 查询，显式排除软删行（保住"软删行 404"语义）。 */
    Optional<Comment> findByIdAndDeletedFalse(UUID id);

    /** 某父评论的回复数（用于顶层评论的 reply_count），排除软删行。 */
    long countByParentCommentIdAndDeletedFalse(UUID parentId);

    /** 校验回复父评论存在、同帖且未删。 */
    Optional<Comment> findByPostIdAndIdAndDeletedFalse(UUID postId, UUID id);

    /** 加载某顶层评论的全部回复（级联软删用），排除软删行。 */
    List<Comment> findAllByParentCommentIdAndDeletedFalse(UUID parentId);
}
