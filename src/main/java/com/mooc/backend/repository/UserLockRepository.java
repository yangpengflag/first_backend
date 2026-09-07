package com.mooc.backend.repository;

import com.mooc.backend.entity.User;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * 用户行悲观锁仓储（messaging 模块自治分片，不修改 auth 包）。
 *
 * <p>用于并发同对会话创建的<b>串行化</b>：{@code createConversation} 按全局一致的
 * 无符号字节序（{@link UuidOrdering}）先锁 low 用户行、再锁 high 用户行——
 * 任意线程对同一对用户都以同一顺序加锁（资源层级序），等待图无环、无死锁；
 * 后到线程阻塞至先到线程提交，其后的会话预查（一致性读）必然命中既有行，
 * 唯一约束冲突路径仅在极端调度下作为兜底存在（design.md「并发创建冲突时
 * 捕获约束异常回读既有会话」）。
 *
 * <p>以 Spring Data 分片接口（同一实体的第二仓储）承载 {@code SELECT ... FOR UPDATE}，
 * 避免触碰 {@code auth.domain.UserRepository}（约束：复用 auth 包仅 import、不修改）。
 */
public interface UserLockRepository extends org.springframework.data.repository.Repository<User, UUID> {

    /**
     * 以悲观写锁加载用户行（MySQL 下生成 {@code SELECT ... FOR UPDATE}，
     * 锁持有至事务结束）。行不存在时返回 empty（不产生锁）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);
}
