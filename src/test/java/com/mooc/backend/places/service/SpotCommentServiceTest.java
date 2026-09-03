package com.mooc.backend.places.service;

import com.mooc.backend.auth.domain.Role;
import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.auth.domain.UserStatus;
import com.mooc.backend.comments.api.CreateCommentRequest;
import com.mooc.backend.comments.exception.CommentException;
import com.mooc.backend.places.api.SpotCommentResponse;
import com.mooc.backend.places.domain.Spot;
import com.mooc.backend.places.domain.SpotComment;
import com.mooc.backend.places.repository.SpotCommentRepository;
import com.mooc.backend.places.repository.SpotRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 景点评论业务逻辑测试（镜像 comments.CommentServiceTest，postId → spotSlug）。
 */
@ExtendWith(MockitoExtension.class)
class SpotCommentServiceTest {

    @Mock
    private SpotCommentRepository spotCommentRepository;

    @Mock
    private SpotRepository spotRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private SpotCommentService spotCommentService;

    private static final String SLUG = "hangzhou-west-lake";
    private static final UUID USER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");

    @Test
    void createTopLevelSucceeds() {
        when(spotRepository.findBySlugAndDeletedFalse(SLUG)).thenReturn(Optional.of(mock(Spot.class)));
        when(spotCommentRepository.save(any(SpotComment.class))).thenAnswer(inv -> inv.getArgument(0));
        User alice = activeUser(USER, "Alice", null);
        when(userRepository.findAllById(anyList())).thenReturn(List.of(alice));

        SpotCommentResponse resp = spotCommentService.create(SLUG, USER, new CreateCommentRequest("Nice!", null), NOW);

        assertThat(resp.getUserId()).isEqualTo(USER);
        assertThat(resp.getAuthorName()).isEqualTo("Alice");
        assertThat(resp.getSpotSlug()).isEqualTo(SLUG);
        assertThat(resp.getParentCommentId()).isNull();
        assertThat(resp.getReplyCount()).isZero();
    }

    @Test
    void createReplyToTopLevelSucceeds() {
        UUID topId = UUID.randomUUID();
        SpotComment top = SpotComment.create(SLUG, OTHER, "top", null, NOW);
        when(spotRepository.findBySlugAndDeletedFalse(SLUG)).thenReturn(Optional.of(mock(Spot.class)));
        when(spotCommentRepository.findBySpotSlugAndIdAndDeletedFalse(SLUG, topId)).thenReturn(Optional.of(top));
        when(spotCommentRepository.save(any(SpotComment.class))).thenAnswer(inv -> inv.getArgument(0));
        User bob = activeUser(USER, "Bob", null);
        when(userRepository.findAllById(anyList())).thenReturn(List.of(bob));

        SpotCommentResponse resp = spotCommentService.create(SLUG, USER, new CreateCommentRequest("reply", topId), NOW);

        assertThat(resp.getParentCommentId()).isEqualTo(topId);
    }

    @Test
    void createReplyCrossSpotIsInvalid() {
        UUID topId = UUID.randomUUID();
        when(spotRepository.findBySlugAndDeletedFalse(SLUG)).thenReturn(Optional.of(mock(Spot.class)));
        when(spotCommentRepository.findBySpotSlugAndIdAndDeletedFalse(SLUG, topId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> spotCommentService.create(SLUG, USER, new CreateCommentRequest("x", topId), NOW))
                .isInstanceOf(CommentException.class);
    }

    @Test
    void createReplyNestedUnderReplyIsInvalid() {
        UUID topId = UUID.randomUUID();
        UUID replyId = UUID.randomUUID();
        SpotComment reply = SpotComment.create(SLUG, OTHER, "r", topId, NOW);
        when(spotRepository.findBySlugAndDeletedFalse(SLUG)).thenReturn(Optional.of(mock(Spot.class)));
        when(spotCommentRepository.findBySpotSlugAndIdAndDeletedFalse(SLUG, replyId)).thenReturn(Optional.of(reply));

        assertThatThrownBy(() -> spotCommentService.create(SLUG, USER, new CreateCommentRequest("x", replyId), NOW))
                .isInstanceOf(CommentException.class);
    }

    @Test
    void createSpotNotFound() {
        when(spotRepository.findBySlugAndDeletedFalse(SLUG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> spotCommentService.create(SLUG, USER, new CreateCommentRequest("x", null), NOW))
                .isInstanceOf(CommentException.class);
    }

    @Test
    void listTopLevelSpotNotFound() {
        when(spotRepository.findBySlugAndDeletedFalse(SLUG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> spotCommentService.listTopLevel(SLUG, 0, 20, NOW))
                .isInstanceOf(CommentException.class);
    }

    @Test
    void listRepliesParentNotFound() {
        when(spotCommentRepository.findByIdAndDeletedFalse(OTHER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> spotCommentService.listReplies(OTHER, 0, 20, NOW))
                .isInstanceOf(CommentException.class);
    }

    @Test
    void deleteByNonAuthorForbidden() {
        SpotComment comment = SpotComment.create(SLUG, OTHER, "c", null, NOW);
        when(spotCommentRepository.findByIdAndDeletedFalse(comment.getId())).thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> spotCommentService.delete(comment.getId(), USER, NOW))
                .isInstanceOf(CommentException.class);

        assertThat(comment.isDeleted()).isFalse();
    }

    @Test
    void deleteByAdminSucceedsEvenIfNotAuthor() {
        SpotComment comment = SpotComment.create(SLUG, OTHER, "c", null, NOW);
        when(spotCommentRepository.findByIdAndDeletedFalse(comment.getId())).thenReturn(Optional.of(comment));
        when(spotCommentRepository.save(any(SpotComment.class))).thenAnswer(inv -> inv.getArgument(0));
        User admin = mock(User.class);
        when(admin.getRole()).thenReturn(Role.ADMIN);
        when(userRepository.findById(USER)).thenReturn(Optional.of(admin));

        spotCommentService.delete(comment.getId(), USER, NOW);

        assertThat(comment.isDeleted()).isTrue();
    }

    @Test
    void deleteMissingCommentNotFound() {
        when(spotCommentRepository.findByIdAndDeletedFalse(OTHER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> spotCommentService.delete(OTHER, USER, NOW))
                .isInstanceOf(CommentException.class);
    }

    @Test
    void deleteTopLevelCascadesReplies() {
        SpotComment top = SpotComment.create(SLUG, USER, "top", null, NOW);
        SpotComment reply1 = SpotComment.create(SLUG, OTHER, "r1", top.getId(), NOW);
        SpotComment reply2 = SpotComment.create(SLUG, OTHER, "r2", top.getId(), NOW);
        when(spotCommentRepository.findByIdAndDeletedFalse(top.getId())).thenReturn(Optional.of(top));
        when(spotCommentRepository.findAllByParentCommentIdAndDeletedFalse(top.getId()))
                .thenReturn(List.of(reply1, reply2));
        when(spotCommentRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(spotCommentRepository.save(any(SpotComment.class))).thenAnswer(inv -> inv.getArgument(0));

        spotCommentService.delete(top.getId(), USER, NOW);

        assertThat(top.isDeleted()).isTrue();
        assertThat(reply1.isDeleted()).isTrue();
        assertThat(reply2.isDeleted()).isTrue();
        verify(spotCommentRepository).saveAll(anyList());
    }

    @Test
    void deleteReplyDoesNotCascade() {
        UUID topId = UUID.randomUUID();
        SpotComment reply = SpotComment.create(SLUG, USER, "r", topId, NOW);
        when(spotCommentRepository.findByIdAndDeletedFalse(reply.getId())).thenReturn(Optional.of(reply));
        when(spotCommentRepository.save(any(SpotComment.class))).thenAnswer(inv -> inv.getArgument(0));

        spotCommentService.delete(reply.getId(), USER, NOW);

        assertThat(reply.isDeleted()).isTrue();
        verify(spotCommentRepository, never()).findAllByParentCommentIdAndDeletedFalse(any());
    }

    @Test
    void listTopLevelCarriesReplyCountAndAuthor() {
        SpotComment top = SpotComment.create(SLUG, OTHER, "top", null, NOW);
        when(spotRepository.findBySlugAndDeletedFalse(SLUG)).thenReturn(Optional.of(mock(Spot.class)));
        when(spotCommentRepository.findBySpotSlugAndParentCommentIdIsNullAndDeletedFalse(eq(SLUG), any()))
                .thenReturn(new PageImpl<>(List.of(top), PageRequest.of(0, 20), 1));
        when(spotCommentRepository.countByParentCommentIdAndDeletedFalse(top.getId())).thenReturn(3L);
        User carol = activeUser(OTHER, "Carol", "http://a");
        when(userRepository.findAllById(anyList())).thenReturn(List.of(carol));

        Page<SpotCommentResponse> page = spotCommentService.listTopLevel(SLUG, 0, 20, NOW);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getReplyCount()).isEqualTo(3);
        assertThat(page.getContent().get(0).getAuthorName()).isEqualTo("Carol");
    }

    @Test
    void authorMissingFallsBackToPlaceholder() {
        SpotComment top = SpotComment.create(SLUG, OTHER, "top", null, NOW);
        when(spotRepository.findBySlugAndDeletedFalse(SLUG)).thenReturn(Optional.of(mock(Spot.class)));
        when(spotCommentRepository.findBySpotSlugAndParentCommentIdIsNullAndDeletedFalse(eq(SLUG), any()))
                .thenReturn(new PageImpl<>(List.of(top), PageRequest.of(0, 20), 1));
        when(spotCommentRepository.countByParentCommentIdAndDeletedFalse(top.getId())).thenReturn(0L);
        when(userRepository.findAllById(anyList())).thenReturn(List.of());

        Page<SpotCommentResponse> page = spotCommentService.listTopLevel(SLUG, 0, 20, NOW);

        assertThat(page.getContent().get(0).getAuthorName()).isEqualTo("[unknown user]");
    }

    private User activeUser(UUID id, String name, String avatar) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.getDisplayName()).thenReturn(name);
        when(user.getAvatarUrl()).thenReturn(avatar);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        return user;
    }
}
