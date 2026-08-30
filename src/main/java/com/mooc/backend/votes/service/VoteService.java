package com.mooc.backend.votes.service;

import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.posts.repository.PostRepository;
import com.mooc.backend.votes.api.VoteResponse;
import com.mooc.backend.votes.api.VoteStatsResponse;
import com.mooc.backend.votes.domain.Vote;
import com.mooc.backend.votes.domain.VoteType;
import com.mooc.backend.votes.exception.VoteException;
import com.mooc.backend.votes.repository.VoteRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 投票业务逻辑。
 *
 * <p>三态语义：不存在→创建；已存在且同类型→取消（物理删除）；已存在且异类型→切换。
 * 一人一票由 {@code votes} 表唯一约束保证；并发创建撞约束时（DVI）在事务内重读真实态返回，保证幂等与最终一致。
 */
@Service
public class VoteService {

    private static final Logger log = LoggerFactory.getLogger(VoteService.class);

    private final VoteRepository voteRepository;
    private final PostRepository postRepository;

    public VoteService(VoteRepository voteRepository, PostRepository postRepository) {
        this.voteRepository = voteRepository;
        this.postRepository = postRepository;
    }

    /** 投票三态；返回当前用户投票态。 */
    @Transactional
    public VoteResponse vote(UUID postId, UUID userId, VoteType voteType, Instant now) {
        if (postRepository.findByIdAndDeletedFalse(postId).isEmpty()) {
            throw new VoteException(ErrorCode.POST_NOT_FOUND);
        }
        Optional<Vote> existing = voteRepository.findByPostIdAndUserId(postId, userId);
        if (existing.isEmpty()) {
            return createVote(postId, userId, voteType, now);
        }
        Vote current = existing.get();
        if (current.getVoteType() == voteType) {
            // 同类型再投 → 取消（物理删除，释放唯一约束槽位）
            voteRepository.delete(current);
            return VoteResponse.from(postId, null);
        }
        // 异类型 → 切换
        current.updateVoteType(voteType, now);
        voteRepository.save(current);
        return VoteResponse.from(postId, voteType.name());
    }

    private VoteResponse createVote(UUID postId, UUID userId, VoteType voteType, Instant now) {
        try {
            Vote vote = Vote.create(postId, userId, voteType, now);
            voteRepository.saveAndFlush(vote);
            return VoteResponse.from(postId, voteType.name());
        } catch (DataIntegrityViolationException ex) {
            // 并发创建撞唯一约束：重读真实状态返回（幂等兜底）
            log.debug("Concurrent vote insert hit unique constraint for post {} user {}, re-reading", postId, userId);
            Vote reread = voteRepository.findByPostIdAndUserId(postId, userId).orElse(null);
            if (reread == null) {
                throw ex;
            }
            return VoteResponse.from(postId, reread.getVoteType().name());
        }
    }

    /** 统计：UP/DOWN 总数 + 当前用户投票态。需鉴权，userId 恒非空。 */
    public VoteStatsResponse getVoteStats(UUID postId, UUID userId) {
        long upCount = voteRepository.countByPostIdAndVoteType(postId, VoteType.UP);
        long downCount = voteRepository.countByPostIdAndVoteType(postId, VoteType.DOWN);
        String userVote = voteRepository.findByPostIdAndUserId(postId, userId)
                .map(v -> v.getVoteType().name())
                .orElse(null);
        return VoteStatsResponse.from(postId, upCount, downCount, userVote);
    }
}
