package com.mooc.backend.votes.domain;

import com.mooc.backend.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * 投票实体（继承 {@code BaseEntity} 软删内核）。
 *
 * <p>一人一票：唯一约束 {@code uk_votes_post_user (post_id, user_id)}。取消投票走物理删除
 * （{@code voteRepository.delete}），以释放唯一约束槽位、允许该用户再次投票；
 * {@code deleted} 列恒为 false。
 */
@Entity
@Table(
        name = "votes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_votes_post_user",
                columnNames = {"post_id", "user_id"}))
public class Vote extends BaseEntity {

    @Column(name = "post_id", nullable = false)
    private UUID postId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "vote_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private VoteType voteType;

    protected Vote() {
        // JPA only
    }

    private Vote(UUID id, UUID postId, UUID userId, VoteType voteType, Instant now) {
        super(id, now);
        this.postId = postId;
        this.userId = userId;
        this.voteType = voteType;
    }

    public static Vote create(UUID postId, UUID userId, VoteType voteType, Instant now) {
        return new Vote(UUID.randomUUID(), postId, userId, voteType, now);
    }

    /** 切换投票类型并刷新更新时间。 */
    public void updateVoteType(VoteType voteType, Instant now) {
        this.voteType = voteType;
        this.touch(now);
    }

    public UUID getPostId() {
        return postId;
    }

    public UUID getUserId() {
        return userId;
    }

    public VoteType getVoteType() {
        return voteType;
    }
}
