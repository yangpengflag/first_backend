package com.mooc.backend.posts.repository;

import com.mooc.backend.posts.domain.Post;
import com.mooc.backend.posts.domain.PostStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * 帖子仓储。
 *
 * <p>软删除通过<b>查询层显式过滤</b>实现：所有对外查询方法均带 {@code AndDeletedFalse} 后缀，
 * 确保已软删的攻略不会出现在列表 / 详情 / 我的帖子中。这与 {@code database-conventions} 约定一致，
 * 也避免了对 {@code User}（需查已删行）施加全局过滤的副作用。
 */
public interface PostRepository extends JpaRepository<Post, UUID>, PostRepositoryCustom {

    /** 按状态分页查询，显式排除软删行。 */
    Page<Post> findByStatusAndDeletedFalse(PostStatus status, Pageable pageable);

    /** 某作者的全部帖子（含 DRAFT 与 PUBLISHED），显式排除软删行。 */
    Page<Post> findByAuthorIdAndDeletedFalse(UUID authorId, Pageable pageable);

    /** 按 id 查询，显式排除软删行（保住"软删行 404"语义）。 */
    Optional<Post> findByIdAndDeletedFalse(UUID id);

    /** PUBLISHED 且未软删的帖子总数（offset 分页 total 用，与排序无关）。 */
    long countByStatusAndDeletedFalse(PostStatus status);

    /** 某作者未软删的帖子总数（offset 分页 total 用）。 */
    long countByAuthorIdAndDeletedFalse(UUID authorId);
}
