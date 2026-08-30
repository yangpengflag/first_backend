package com.mooc.backend.bookmarks.repository;

import com.mooc.backend.bookmarks.domain.Bookmark;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * 收藏仓储。一人一帖唯一约束由 {@code bookmarks} 表保证；取消收藏经物理删除实现。
 */
public interface BookmarkRepository extends JpaRepository<Bookmark, UUID> {

    /** 某用户的收藏，按收藏时间倒序（最近在前）。 */
    Page<Bookmark> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /** 取某用户对某帖的收藏（用于切换）。 */
    Optional<Bookmark> findByPostIdAndUserId(UUID postId, UUID userId);
}
