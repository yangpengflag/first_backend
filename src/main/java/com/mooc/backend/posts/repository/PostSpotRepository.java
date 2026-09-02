package com.mooc.backend.posts.repository;

import com.mooc.backend.posts.domain.PostSpot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * 帖子 ↔ 景点关联表仓储。
 *
 * <p>{@code deleteByPostId} 用于写路径刷新某帖的全部景点关联（先删后插）；
 * {@code findByPostId} 供反查某帖关联的所有景点 slug。
 */
public interface PostSpotRepository extends JpaRepository<PostSpot, UUID> {

    List<PostSpot> findByPostId(UUID postId);

    void deleteByPostId(UUID postId);
}
