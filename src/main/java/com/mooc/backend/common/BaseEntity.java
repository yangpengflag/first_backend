package com.mooc.backend.common;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 所有 JPA 实体的公共基类（共享内核）。
 *
 * <p>统一承载主键与审计时间戳，避免每个实体重复样板。子类在工厂方法中以
 * {@code super(id, now)} 设定主键与创建/更新时间，并在业务变更末尾调用
 * {@link #touch(Instant)} 刷新更新时间——时间由调用方注入，便于测试，
 * 与 {@code User} 既有的显式时间注入约定一致。
 *
 * <p><b>软删除</b>：本基类携带 {@code deleted} 布尔标志（默认 false），但<b>不</b>在基类上施加
 * 任何全局过滤。原因：{@code User} 的鉴权逻辑（登录 / 令牌校验）必须能查到
 * 已软删的行以返回精确的 {@code ACCOUNT_DELETED} 响应，全局过滤会破坏该语义。
 * 需要自动排除已删行的实体，应在<b>仓储层</b>使用 {@code findByXxxAndDeletedFalse} 显式过滤
 * （见 {@code PostRepository}），而非 {@code @SQLRestriction}，以贴合 {@code database-conventions} 约定。
 */
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted", nullable = false, columnDefinition = "tinyint(1)")
    private boolean deleted = false;

    protected BaseEntity() {
        // JPA only
    }

    protected BaseEntity(UUID id, Instant now) {
        this.id = id;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 业务方法在变更后调用，刷新更新时间戳。时间由调用方注入，便于测试。 */
    protected void touch(Instant now) {
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isDeleted() {
        return deleted;
    }

    /** 软删除：置 {@code deleted} 标志，行保留。子类（如 {@code User.softDelete}）负责维护审计时间戳。 */
    public void markDeleted() {
        this.deleted = true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        BaseEntity other = (BaseEntity) o;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
