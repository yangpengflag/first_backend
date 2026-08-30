package com.mooc.backend.bookmarks.repository;

import com.mooc.backend.bookmarks.domain.Bookmark;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 仓储层测试：验证一人一帖唯一约束与按收藏时间倒序。
 */
@SpringBootTest
@Transactional
class BookmarkRepositoryTest {

    @Autowired
    private BookmarkRepository bookmarkRepository;

    private static final Instant NOW = Instant.now();

    @Test
    void uniqueConstraintRejectsDuplicatePostUserPair() {
        UUID postId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        bookmarkRepository.saveAndFlush(Bookmark.create(postId, userId, NOW));

        assertThatThrownBy(() -> bookmarkRepository.saveAndFlush(Bookmark.create(postId, userId, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByUserIdOrdersByCreatedAtDesc() {
        UUID user = UUID.randomUUID();
        Bookmark older = bookmarkRepository.saveAndFlush(Bookmark.create(UUID.randomUUID(), user, NOW.minusSeconds(10)));
        Bookmark newer = bookmarkRepository.saveAndFlush(Bookmark.create(UUID.randomUUID(), user, NOW));

        Page<Bookmark> page = bookmarkRepository.findByUserIdOrderByCreatedAtDesc(user, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getId()).isEqualTo(newer.getId());
        assertThat(page.getContent().get(1).getId()).isEqualTo(older.getId());
    }
}
