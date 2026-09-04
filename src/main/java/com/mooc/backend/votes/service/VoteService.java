package com.mooc.backend.votes.service;

import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.notifications.domain.NotificationType;
import com.mooc.backend.notifications.service.NotificationService;
import com.mooc.backend.posts.domain.Post;
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
 *
 * <p><b>通知挂接（Task 2.3，同事务直调）</b>：投 UP 成功后给帖主产生 {@code POST_LIKED}
 * 未读通知（自投短路由 {@code NotificationService} 内部短路，此处无脑下发）；
 * DOWN 创建不产生通知。UP 的取消（同类型再投物理删除、异类型切换为 DOWN）语义上
 * 「该 UP 互动不复存在」，撤销其产生的未读通知（已读保留）；DOWN→UP 视为新一次互动。
 * DVI 兜底路径不算本线程成功创建，通知由成功线程负责，不再下发。
 */
@Service
public class VoteService {

    private static final Logger log = LoggerFactory.getLogger(VoteService.class);

    private final VoteRepository voteRepository;
    private final PostRepository postRepository;
    private final NotificationService notificationService;

    public VoteService(VoteRepository voteRepository, PostRepository postRepository,
                       NotificationService notificationService) {
        this.voteRepository = voteRepository;
        this.postRepository = postRepository;
        this.notificationService = notificationService;
    }

    /** 投票三态；返回当前用户投票态。 */
    @Transactional
    public VoteResponse vote(UUID postId, UUID userId, VoteType voteType, Instant now) {
        Post post = postRepository.findByIdAndDeletedFalse(postId)
                .orElseThrow(() -> new VoteException(ErrorCode.POST_NOT_FOUND));
        UUID recipientId = post.getAuthorId();
        Optional<Vote> existing = voteRepository.findByPostIdAndUserId(postId, userId);
        if (existing.isEmpty()) {
            return createVote(postId, userId, recipientId, voteType, now);
        }
        Vote current = existing.get();
        if (current.getVoteType() == voteType) {
            // 同类型再投 → 取消（物理删除，释放唯一约束槽位）
            voteRepository.delete(current);
            if (voteType == VoteType.UP) {
                notificationService.onInteractionRemoved(userId, NotificationType.POST_LIKED, postId, null);
            }
            return VoteResponse.from(postId, null);
        }
        // 异类型 → 切换
        VoteType previous = current.getVoteType();
        current.updateVoteType(voteType, now);
        voteRepository.save(current);
        if (previous == VoteType.UP) {
            // UP → DOWN：原 UP 互动不复存在，撤销其产生的未读通知
            notificationService.onInteractionRemoved(userId, NotificationType.POST_LIKED, postId, null);
        } else if (voteType == VoteType.UP) {
            // DOWN → UP：视为新一次 UP 互动
            notificationService.onInteraction(userId, recipientId, NotificationType.POST_LIKED, postId, null, now);
        }
        return VoteResponse.from(postId, voteType.name());
    }

    private VoteResponse createVote(UUID postId, UUID userId, UUID recipientId,
                                    VoteType voteType, Instant now) {
        try {
            Vote vote = Vote.create(postId, userId, voteType, now);
            voteRepository.saveAndFlush(vote);
            if (voteType == VoteType.UP) {
                notificationService.onInteraction(userId, recipientId, NotificationType.POST_LIKED, postId, null, now);
            }
            return VoteResponse.from(postId, voteType.name());
        } catch (DataIntegrityViolationException ex) {
            // 并发创建撞唯一约束：重读真实状态返回（幂等兜底）；通知由成功创建的线程负责
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
