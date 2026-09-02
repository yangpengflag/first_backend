package com.mooc.backend.posts.domain;

import com.mooc.backend.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 旅行攻略（Story）实体。
 *
 * <p>继承 {@code BaseEntity} 共享主键与审计时间戳。软删除通过<b>仓储层</b>
 * {@code findByXxxAndDeletedFalse} 显式过滤实现（不使用 {@code @SQLRestriction} 全局过滤），
 * 以贴合 {@code database-conventions} 约定；{@code User} 因鉴权需查已删行，同样不加全局过滤。
 *
 * <p>{@code summary} 不存储，读取时由 {@code MarkdownSummary} 从 {@code content} 派生。
 */
@Entity
@Table(name = "posts", indexes = {
        @Index(name = "idx_posts_city_id", columnList = "city_id")
})
public class Post extends BaseEntity {

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "cover_image_url")
    private String coverImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PostStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", nullable = false)
    private List<String> tags = new ArrayList<>();

    /** 单城市语境地点关联（存 city slug，可选）。 */
    @Column(name = "city_id")
    private String cityId;

    /** 多 POI 关联（存 Spot slug 数组，可选，缺省空数组）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spot_ids", nullable = false)
    private List<String> spotIds = new ArrayList<>();

    protected Post() {
        // JPA only
    }

    private Post(UUID id, UUID authorId, String title, String content,
                 String coverImageUrl, List<String> tags, PostStatus status,
                 String cityId, List<String> spotIds, Instant now) {
        super(id, now);
        this.authorId = authorId;
        this.title = title;
        this.content = content;
        this.coverImageUrl = coverImageUrl;
        this.tags = new ArrayList<>(tags);
        this.status = status;
        this.cityId = cityId;
        this.spotIds = spotIds == null ? new ArrayList<>() : new ArrayList<>(spotIds);
    }

    /** 创建新帖子，主键与时间由调用方注入（与 BaseEntity / User 约定一致）。 */
    public static Post create(UUID authorId, String title, String content,
                              String coverImageUrl, List<String> tags, PostStatus status,
                              String cityId, List<String> spotIds, Instant now) {
        return new Post(UUID.randomUUID(), authorId, title, content, coverImageUrl, tags, status,
                cityId, spotIds, now);
    }

    /** 局部更新：仅替换调用方提供的非空字段，并刷新更新时间。地点字段为 null 时保留原值。 */
    public void update(String title, String content, String coverImageUrl,
                       List<String> tags, PostStatus status, String cityId, List<String> spotIds,
                       Instant now) {
        this.title = title;
        this.content = content;
        this.coverImageUrl = coverImageUrl;
        this.tags.clear();
        this.tags.addAll(tags);
        this.status = status;
        if (cityId != null) {
            this.cityId = cityId;
        }
        if (spotIds != null) {
            this.spotIds = new ArrayList<>(spotIds);
        }
        this.touch(now);
    }

    /** 软删除：置 deleted 标志并刷新更新时间（行保留，查询层 AndDeletedFalse 自动排除）。 */
    public void softDelete(Instant now) {
        this.markDeleted();
        this.touch(now);
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public String getCoverImageUrl() {
        return coverImageUrl;
    }

    public PostStatus getStatus() {
        return status;
    }

    public List<String> getTags() {
        return List.copyOf(tags);
    }

    public String getCityId() {
        return cityId;
    }

    public List<String> getSpotIds() {
        return List.copyOf(spotIds);
    }

    public boolean isPublished() {
        return status == PostStatus.PUBLISHED;
    }
}
