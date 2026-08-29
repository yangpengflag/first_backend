package com.mooc.backend.posts.domain;

import com.mooc.backend.common.BaseEntity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 旅行攻略（Story）实体。
 *
 * <p>继承 {@code BaseEntity} 共享主键与审计时间戳。<b>普通业务实体</b>，故在类上声明
 * {@code @SQLRestriction("deleted_at IS NULL")}——与 {@code User} 故意省略该注解相反，
 * 因为攻略查询无需命中已软删行（见 BaseEntity 注释「后续业务模块在自身类声明」）。
 *
 * <p>{@code summary} 不存储，读取时由 {@code MarkdownSummary} 从 {@code content} 派生。
 */
@Entity
@Table(name = "posts")
@SQLRestriction("deleted_at IS NULL")
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

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "post_tags", joinColumns = @JoinColumn(name = "post_id"))
    @Column(name = "tag", length = 30)
    private List<String> tags = new ArrayList<>();

    protected Post() {
        // JPA only
    }

    private Post(UUID id, UUID authorId, String title, String content,
                 String coverImageUrl, List<String> tags, PostStatus status, Instant now) {
        super(id, now);
        this.authorId = authorId;
        this.title = title;
        this.content = content;
        this.coverImageUrl = coverImageUrl;
        this.tags = new ArrayList<>(tags);
        this.status = status;
    }

    /** 创建新帖子，主键与时间由调用方注入（与 BaseEntity / User 约定一致）。 */
    public static Post create(UUID authorId, String title, String content,
                              String coverImageUrl, List<String> tags, PostStatus status, Instant now) {
        return new Post(UUID.randomUUID(), authorId, title, content, coverImageUrl, tags, status, now);
    }

    /** 局部更新：仅替换调用方提供的非空字段，并刷新更新时间。 */
    public void update(String title, String content, String coverImageUrl,
                       List<String> tags, PostStatus status, Instant now) {
        this.title = title;
        this.content = content;
        this.coverImageUrl = coverImageUrl;
        this.tags.clear();
        this.tags.addAll(tags);
        this.status = status;
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

    public boolean isPublished() {
        return status == PostStatus.PUBLISHED;
    }
}
