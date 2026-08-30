package com.mooc.backend.posts.repository;

import com.mooc.backend.posts.domain.Post;
import com.mooc.backend.posts.domain.PostStatus;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仓储层测试：验证 {@code AndDeletedFalse} 查询层软删过滤与 tags 集合持久化。
 *
 * <p>软删通过原生 UPDATE + 清空持久化上下文模拟，确保查询真正落库并应用过滤条件。
 */
@SpringBootTest
@Transactional
class PostRepositoryTest {

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void softDeletedPostExcludedFromQueries() {
        Post post = Post.create(UUID.randomUUID(), "T", "c", null, List.of(), PostStatus.PUBLISHED, Instant.now());
        Post saved = postRepository.saveAndFlush(post);

        entityManager.createNativeQuery("UPDATE posts SET deleted = true WHERE id = ?1")
                .setParameter(1, saved.getId())
                .executeUpdate();
        entityManager.clear();

        assertThat(postRepository.findByIdAndDeletedFalse(saved.getId())).isEmpty();
        assertThat(postRepository.findByStatusAndDeletedFalse(PostStatus.PUBLISHED, PageRequest.of(0, 10)).getContent())
                .noneMatch(p -> p.getId().equals(saved.getId()));
    }

    @Test
    void tagsPersistedAndLoaded() {
        Post post = Post.create(UUID.randomUUID(), "T", "c", null,
                List.of("Hiking", "Sichuan"), PostStatus.DRAFT, Instant.now());
        Post saved = postRepository.saveAndFlush(post);
        entityManager.clear();

        Post reloaded = postRepository.findByIdAndDeletedFalse(saved.getId()).orElseThrow();
        assertThat(reloaded.getTags()).containsExactly("Hiking", "Sichuan");
    }
}
