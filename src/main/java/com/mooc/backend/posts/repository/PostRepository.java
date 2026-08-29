package com.mooc.backend.posts.repository;

import com.mooc.backend.posts.domain.Post;
import com.mooc.backend.posts.domain.PostStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * 帖子仓储。
 *
 * <p>{@code @SQLRestriction} 已声明于 {@link Post} 实体，故所有查询（含 {@code findById}）
 * 自动排除软删除行——已软删的攻略不会出现在列表 / 详情 / 我的帖子中。
 */
public interface PostRepository extends JpaRepository<Post, UUID> {

    /** 按状态分页查询（配合 @SQLRestriction 自动排除软删）。 */
    Page<Post> findByStatus(PostStatus status, Pageable pageable);

    /** 某作者的全部帖子（含 DRAFT 与 PUBLISHED；软删已被 @SQLRestriction 排除）。 */
    Page<Post> findByAuthorId(UUID authorId, Pageable pageable);
}
