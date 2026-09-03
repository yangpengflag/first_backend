package com.mooc.backend.places.repository;

import com.mooc.backend.places.domain.SpotComment;

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
 * 景点评论仓储层测试（镜像 comments.CommentRepositoryTest，postId → spotSlug）。
 *
 * <p>软删通过原生 UPDATE + 清空持久化上下文模拟，确保查询真正落库并应用过滤条件。
 */
@SpringBootTest
@Transactional
class SpotCommentRepositoryTest {

    @Autowired
    private SpotCommentRepository spotCommentRepository;

    @Autowired
    private EntityManager entityManager;

    private SpotComment saved(String spotSlug, UUID parentId) {
        return spotCommentRepository.saveAndFlush(SpotComment.create(spotSlug, UUID.randomUUID(), "c", parentId, Instant.now()));
    }

    @Test
    void softDeletedCommentExcludedFromQueries() {
        SpotComment c = saved("s1", null);

        entityManager.createNativeQuery("UPDATE spot_comments SET deleted = true WHERE id = ?1")
                .setParameter(1, c.getId())
                .executeUpdate();
        entityManager.clear();

        assertThat(spotCommentRepository.findByIdAndDeletedFalse(c.getId())).isEmpty();
        assertThat(spotCommentRepository.findBySpotSlugAndParentCommentIdIsNullAndDeletedFalse(
                c.getSpotSlug(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent())
                .noneMatch(x -> x.getId().equals(c.getId()));
    }

    @Test
    void countByParentCountsOnlyDirectReplies() {
        String slug = "s1";
        SpotComment top = saved(slug, null);
        saved(slug, top.getId());
        saved(slug, top.getId());
        SpotComment otherTop = saved(slug, null);

        assertThat(spotCommentRepository.countByParentCommentIdAndDeletedFalse(top.getId())).isEqualTo(2);
        assertThat(spotCommentRepository.countByParentCommentIdAndDeletedFalse(otherTop.getId())).isEqualTo(0);
    }

    @Test
    void findAllByParentLoadsRepliesForCascade() {
        String slug = "s1";
        SpotComment top = saved(slug, null);
        saved(slug, top.getId());
        saved(slug, top.getId());

        List<SpotComment> replies = spotCommentRepository.findAllByParentCommentIdAndDeletedFalse(top.getId());
        assertThat(replies).hasSize(2);
    }
}
