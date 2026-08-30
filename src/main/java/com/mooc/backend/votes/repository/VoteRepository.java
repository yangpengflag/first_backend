package com.mooc.backend.votes.repository;

import com.mooc.backend.votes.domain.Vote;
import com.mooc.backend.votes.domain.VoteType;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * 投票仓储。一人一票由 {@code votes} 表的唯一约束保证；取消投票经物理删除实现。
 */
public interface VoteRepository extends JpaRepository<Vote, UUID> {

    /** 取某用户在某帖的当前投票（用于三态切换）；唯一约束保证至多一行。 */
    Optional<Vote> findByPostIdAndUserId(UUID postId, UUID userId);

    /** 统计某帖某类型的投票数（UP / DOWN）。 */
    int countByPostIdAndVoteType(UUID postId, VoteType voteType);
}
