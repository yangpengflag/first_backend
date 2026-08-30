package com.mooc.backend.comments.repository;

import com.mooc.backend.comments.domain.Comment;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仓储层测试：验证 {@code AndDeletedFalse} 查询层软删过滤、回复计数与级联加载。
 *
 * <p>软删通过原生 UPDATE + 清空持久化上下文模拟，确保查询真正落库并应用过滤条件。
 */
@SpringBootTest
@Transactional
class CommentRepositoryTest {

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private EntityManager entityManager;

    private Comment saved(UUID postId, UUID parentId) {
        return commentRepository.saveAndFlush(Comment.create(postId, UUID.randomUUID(), "c", parentId, Instant.now()));
    }

    @Test
    void softDeletedCommentExcludedFromQueries() {
        Comment c = saved(UUID.randomUUID(), null);

        entityManager.createNativeQuery("UPDATE comments SET deleted = true WHERE id = ?1")
                .setParameter(1, c.getId())
                .executeUpdate();
        entityManager.clear();

        assertThat(commentRepository.findByIdAndDeletedFalse(c.getId())).isEmpty();
        assertThat(commentRepository.findByPostIdAndParentCommentIdIsNullAndDeletedFalse(
                c.getPostId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent())
                .noneMatch(x -> x.getId().equals(c.getId()));
    }

    @Test
    void countByParentCountsOnlyDirectReplies() {
        UUID postId = UUID.randomUUID();
        Comment top = saved(postId, null);
        saved(postId, top.getId());
        saved(postId, top.getId());
        Comment otherTop = saved(postId, null);

        assertThat(commentRepository.countByParentCommentIdAndDeletedFalse(top.getId())).isEqualTo(2);
        assertThat(commentRepository.countByParentCommentIdAndDeletedFalse(otherTop.getId())).isEqualTo(0);
    }

    @Test
    void findAllByParentLoadsRepliesForCascade() {
        UUID postId = UUID.randomUUID();
        Comment top = saved(postId, null);
        saved(postId, top.getId());
        saved(postId, top.getId());

        List<Comment> replies = commentRepository.findAllByParentCommentIdAndDeletedFalse(top.getId());
        assertThat(replies).hasSize(2);
    }
}
