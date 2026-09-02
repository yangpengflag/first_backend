package com.mooc.backend.posts.repository;

import com.mooc.backend.bookmarks.domain.Bookmark;
import com.mooc.backend.comments.domain.Comment;
import com.mooc.backend.posts.api.PostStatsView;
import com.mooc.backend.posts.domain.Post;
import com.mooc.backend.posts.domain.PostStatus;
import com.mooc.backend.votes.domain.Vote;
import com.mooc.backend.votes.domain.VoteType;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仓储层测试：验证 {@code AndDeletedFalse} 查询层软删过滤、tags JSON 列持久化，
 * 以及 {@code findPublishedStats} 的原生聚合查询（计数口径 / 排序 / 游标）。
 *
 * <p>软删通过原生 UPDATE / 实体 {@code softDelete} + 清空持久化上下文或同事务 flush 模拟，
 * 确保查询真正落库并应用过滤条件。测试在 {@code @Transactional} 下运行，结束后自动回滚，不污染库。
 */
@SpringBootTest
@Transactional
class PostRepositoryTest {

    @Autowired
    private PostRepository postRepository;

    @Autowired
    private EntityManager entityManager;

    /**
     * 测试前清空聚合相关的四张表，保证列表聚合查询只看到本测试构造的数据。
     * 整个测试运行在 {@code @Transactional} 下，DELETE 会在事务结束时回滚，不破坏库中既有数据。
     */
    @BeforeEach
    void cleanPostsTables() {
        entityManager.createNativeQuery("DELETE FROM votes").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM bookmarks").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM comments").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM posts").executeUpdate();
    }

    @Test
    void softDeletedPostExcludedFromQueries() {
        Post post = Post.create(UUID.randomUUID(), "T", "c", null, List.of(), PostStatus.PUBLISHED, null, List.of(), Instant.now());
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
                List.of("Hiking", "Sichuan"), PostStatus.DRAFT, null, List.of(), Instant.now());
        Post saved = postRepository.saveAndFlush(post);
        entityManager.clear();

        Post reloaded = postRepository.findByIdAndDeletedFalse(saved.getId()).orElseThrow();
        assertThat(reloaded.getTags()).containsExactly("Hiking", "Sichuan");
    }

    /** 聚合计数口径：草稿帖/已删帖排除；已删评论/收藏不计入；DOWN 票不计入 up 票；多对多不产生叉乘膨胀。 */
    @Test
    void publishedStatsAggregatesCountsWithoutFanout() {
        Seed seed = seedWithStats();
        UUID postA = seed.postA();
        UUID postB = seed.postB();

        List<PostStatsView> stats = postRepository.findPublishedStats(PostSort.LATEST, 10, 0, null, null, false);

        assertThat(stats).extracting(PostStatsView::postId).containsExactlyInAnyOrder(postA, postB);

        PostStatsView a = stats.stream().filter(v -> v.postId().equals(postA)).findFirst().orElseThrow();
        assertThat(a.commentCount()).isEqualTo(1);   // 仅 1 条未删评论（1 条已删不计入）
        assertThat(a.upVoteCount()).isEqualTo(2);    // 2 个 UP（1 个 DOWN 不计入）
        assertThat(a.bookmarkCount()).isEqualTo(1);  // 仅 1 条未删收藏（1 条已删不计入）

        PostStatsView b = stats.stream().filter(v -> v.postId().equals(postB)).findFirst().orElseThrow();
        assertThat(b.commentCount()).isEqualTo(2);
        assertThat(b.upVoteCount()).isEqualTo(1);
        assertThat(b.bookmarkCount()).isEqualTo(1);
    }

    @Test
    void publishedStatsSortsByTopAndMostCommented() {
        Seed seed = seedWithStats();
        UUID postA = seed.postA();
        UUID postB = seed.postB();

        List<PostStatsView> top = postRepository.findPublishedStats(PostSort.TOP, 10, 0, null, null, false);
        assertThat(top).extracting(PostStatsView::postId).containsExactly(postA, postB); // A up=2 在前

        List<PostStatsView> commented = postRepository.findPublishedStats(PostSort.MOST_COMMENTED, 10, 0, null, null, false);
        assertThat(commented).extracting(PostStatsView::postId).containsExactly(postB, postA); // B 评论=2 在前
    }

    /** 游标分页（latest）：游标设在最新帖 B 上，应只返回更早的 A，且 A 计数正确。 */
    @Test
    void publishedStatsCursorExcludesSeenPost() {
        Seed seed = seedWithStats();
        UUID postA = seed.postA();
        UUID postB = seed.postB();

        List<PostStatsView> page = postRepository.findPublishedStats(
                PostSort.LATEST, 10, 0, seed.postBCreatedAt(), postB, true);

        assertThat(page).extracting(PostStatsView::postId).containsExactly(postA);
        assertThat(page.get(0).commentCount()).isEqualTo(1);
        assertThat(page.get(0).upVoteCount()).isEqualTo(2);
        assertThat(page.get(0).bookmarkCount()).isEqualTo(1);
    }

    /** 地点过滤：cityId 精确匹配、spotId 命中 spot_ids 数组（JSON_CONTAINS），且忽略草稿与无地点帖。 */
    @Test
    void locationFilterMatchesCityAndSpot() {
        UUID author = UUID.randomUUID();
        Post hangzhouPost = postRepository.saveAndFlush(
                Post.create(author, "HZ Post", "c", null, List.of(), PostStatus.PUBLISHED,
                        "hangzhou", List.of("hangzhou-west-lake", "lingyin"), Instant.now()));
        postRepository.saveAndFlush(
                Post.create(author, "Other", "c", null, List.of(), PostStatus.PUBLISHED,
                        "chengdu", List.of(), Instant.now()));
        postRepository.saveAndFlush(
                Post.create(author, "None", "c", null, List.of(), PostStatus.PUBLISHED,
                        null, List.of(), Instant.now()));
        postRepository.saveAndFlush(
                Post.create(author, "Draft", "c", null, List.of(), PostStatus.DRAFT,
                        null, List.of("hangzhou-west-lake"), Instant.now()));
        entityManager.clear();

        List<PostStatsView> byCity = postRepository.findPublishedByLocation(PostSort.LATEST, 10, 0, "hangzhou", null);
        assertThat(byCity).extracting(PostStatsView::postId).containsExactly(hangzhouPost.getId());

        List<PostStatsView> bySpot = postRepository.findPublishedByLocation(PostSort.LATEST, 10, 0, null, "hangzhou-west-lake");
        assertThat(bySpot).extracting(PostStatsView::postId).containsExactly(hangzhouPost.getId());

        assertThat(postRepository.countPublishedByLocation("hangzhou", null)).isEqualTo(1);
        assertThat(postRepository.countPublishedByLocation(null, "hangzhou-west-lake")).isEqualTo(1);
        assertThat(postRepository.countPublishedByLocation("chengdu", null)).isEqualTo(1);
        assertThat(postRepository.countPublishedByLocation(null, "lingyin")).isEqualTo(1);
    }

    /**
     * 构建 2 个 PUBLISHED 帖 + 1 个 DRAFT 帖 + 1 个已软删 PUBLISHED 帖，并挂上用于验证计数口径的
     * 评论 / 投票 / 收藏（含已软删与 DOWN 票）。返回两个 PUBLISHED 帖的 id 与 B 的创建时间（游标用）。
     */
    private Seed seedWithStats() {
        Instant base = Instant.now();
        UUID author = UUID.randomUUID();
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        UUID user3 = UUID.randomUUID();

        Post postA = postRepository.saveAndFlush(
                Post.create(author, "A", "c", null, List.of(), PostStatus.PUBLISHED, null, List.of(), base.minus(Duration.ofHours(2))));
        Post postB = postRepository.saveAndFlush(
                Post.create(author, "B", "c", null, List.of(), PostStatus.PUBLISHED, null, List.of(), base.minus(Duration.ofHours(1))));
        postRepository.saveAndFlush(
                Post.create(author, "C", "c", null, List.of(), PostStatus.DRAFT, null, List.of(), base.minus(Duration.ofHours(3))));
        Post postD = postRepository.saveAndFlush(
                Post.create(author, "D", "c", null, List.of(), PostStatus.PUBLISHED, null, List.of(), base));
        postD.softDelete(base);
        postRepository.saveAndFlush(postD);

        // Post A：1 条未删评论（1 条已删）、2 个 UP（1 个 DOWN 忽略）、1 条未删收藏（1 条已删）
        entityManager.persist(Comment.create(postA.getId(), user1, "x", null, base));
        Comment aDeletedComment = Comment.create(postA.getId(), user1, "x", null, base);
        aDeletedComment.softDelete(base);
        entityManager.persist(aDeletedComment);
        entityManager.persist(Vote.create(postA.getId(), user1, VoteType.UP, base));
        entityManager.persist(Vote.create(postA.getId(), user2, VoteType.UP, base));
        entityManager.persist(Vote.create(postA.getId(), user3, VoteType.DOWN, base));
        entityManager.persist(Bookmark.create(postA.getId(), user1, base));
        Bookmark aDeletedBookmark = Bookmark.create(postA.getId(), user2, base);
        aDeletedBookmark.markDeleted();
        entityManager.persist(aDeletedBookmark);

        // Post B：2 条未删评论、1 个 UP、1 条未删收藏
        entityManager.persist(Comment.create(postB.getId(), user1, "x", null, base));
        entityManager.persist(Comment.create(postB.getId(), user1, "x", null, base));
        entityManager.persist(Vote.create(postB.getId(), user1, VoteType.UP, base));
        entityManager.persist(Bookmark.create(postB.getId(), user1, base));

        entityManager.flush();
        return new Seed(postA.getId(), postB.getId(), postB.getCreatedAt());
    }

    private record Seed(UUID postA, UUID postB, Instant postBCreatedAt) {
    }
}
