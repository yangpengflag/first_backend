package com.mooc.backend.comments.service;

import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.auth.domain.UserStatus;
import com.mooc.backend.comments.api.CommentResponse;
import com.mooc.backend.comments.api.CreateCommentRequest;
import com.mooc.backend.comments.domain.Comment;
import com.mooc.backend.comments.exception.CommentException;
import com.mooc.backend.comments.repository.CommentRepository;
import com.mooc.backend.posts.domain.Post;
import com.mooc.backend.posts.repository.PostRepository;

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
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private PostRepository postRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CommentService commentService;

    private static final UUID POST = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");

    @Test
    void createTopLevelSucceeds() {
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(mock(Post.class)));
        when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));
        User alice = activeUser(USER, "Alice", null);
        when(userRepository.findAllById(anyList())).thenReturn(List.of(alice));

        CommentResponse resp = commentService.create(POST, USER, new CreateCommentRequest("Nice!", null), NOW);

        assertThat(resp.getUserId()).isEqualTo(USER);
        assertThat(resp.getAuthorName()).isEqualTo("Alice");
        assertThat(resp.getParentCommentId()).isNull();
        assertThat(resp.getReplyCount()).isZero();
    }

    @Test
    void createReplyToTopLevelSucceeds() {
        UUID topId = UUID.randomUUID();
        Comment top = Comment.create(POST, OTHER, "top", null, NOW);
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(mock(Post.class)));
        when(commentRepository.findByPostIdAndIdAndDeletedFalse(POST, topId)).thenReturn(Optional.of(top));
        when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));
        User bob = activeUser(USER, "Bob", null);
        when(userRepository.findAllById(anyList())).thenReturn(List.of(bob));

        CommentResponse resp = commentService.create(POST, USER, new CreateCommentRequest("reply", topId), NOW);

        assertThat(resp.getParentCommentId()).isEqualTo(topId);
    }

    @Test
    void createReplyCrossPostIsInvalid() {
        UUID topId = UUID.randomUUID();
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(mock(Post.class)));
        when(commentRepository.findByPostIdAndIdAndDeletedFalse(POST, topId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.create(POST, USER, new CreateCommentRequest("x", topId), NOW))
                .isInstanceOf(CommentException.class);
    }

    @Test
    void createReplyNestedUnderReplyIsInvalid() {
        UUID topId = UUID.randomUUID();
        UUID replyId = UUID.randomUUID();
        Comment reply = Comment.create(POST, OTHER, "r", topId, NOW); // 本身是回复
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(mock(Post.class)));
        when(commentRepository.findByPostIdAndIdAndDeletedFalse(POST, replyId)).thenReturn(Optional.of(reply));

        assertThatThrownBy(() -> commentService.create(POST, USER, new CreateCommentRequest("x", replyId), NOW))
                .isInstanceOf(CommentException.class);
    }

    @Test
    void createPostNotFound() {
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.create(POST, USER, new CreateCommentRequest("x", null), NOW))
                .isInstanceOf(CommentException.class);
    }

    @Test
    void listTopLevelPostNotFound() {
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.listTopLevel(POST, 0, 20, NOW))
                .isInstanceOf(CommentException.class);
    }

    @Test
    void listRepliesParentNotFound() {
        when(commentRepository.findByIdAndDeletedFalse(OTHER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.listReplies(OTHER, 0, 20, NOW))
                .isInstanceOf(CommentException.class);
    }

    @Test
    void deleteByNonAuthorForbidden() {
        Comment comment = Comment.create(POST, OTHER, "c", null, NOW);
        when(commentRepository.findByIdAndDeletedFalse(comment.getId())).thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> commentService.delete(comment.getId(), USER, NOW))
                .isInstanceOf(CommentException.class);

        assertThat(comment.isDeleted()).isFalse();
    }

    @Test
    void deleteMissingCommentNotFound() {
        when(commentRepository.findByIdAndDeletedFalse(OTHER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.delete(OTHER, USER, NOW))
                .isInstanceOf(CommentException.class);
    }

    @Test
    void deleteTopLevelCascadesReplies() {
        Comment top = Comment.create(POST, USER, "top", null, NOW);
        Comment reply1 = Comment.create(POST, OTHER, "r1", top.getId(), NOW);
        Comment reply2 = Comment.create(POST, OTHER, "r2", top.getId(), NOW);
        when(commentRepository.findByIdAndDeletedFalse(top.getId())).thenReturn(Optional.of(top));
        when(commentRepository.findAllByParentCommentIdAndDeletedFalse(top.getId()))
                .thenReturn(List.of(reply1, reply2));
        when(commentRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));

        commentService.delete(top.getId(), USER, NOW);

        assertThat(top.isDeleted()).isTrue();
        assertThat(reply1.isDeleted()).isTrue();
        assertThat(reply2.isDeleted()).isTrue();
        verify(commentRepository).saveAll(anyList());
    }

    @Test
    void deleteReplyDoesNotCascade() {
        UUID topId = UUID.randomUUID();
        Comment reply = Comment.create(POST, USER, "r", topId, NOW);
        when(commentRepository.findByIdAndDeletedFalse(reply.getId())).thenReturn(Optional.of(reply));
        when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> inv.getArgument(0));

        commentService.delete(reply.getId(), USER, NOW);

        assertThat(reply.isDeleted()).isTrue();
        verify(commentRepository, never()).findAllByParentCommentIdAndDeletedFalse(any());
    }

    @Test
    void listTopLevelCarriesReplyCountAndAuthor() {
        Comment top = Comment.create(POST, OTHER, "top", null, NOW);
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(mock(Post.class)));
        when(commentRepository.findByPostIdAndParentCommentIdIsNullAndDeletedFalse(eq(POST), any()))
                .thenReturn(new PageImpl<>(List.of(top), PageRequest.of(0, 20), 1));
        when(commentRepository.countByParentCommentIdAndDeletedFalse(top.getId())).thenReturn(3L);
        User carol = activeUser(OTHER, "Carol", "http://a");
        when(userRepository.findAllById(anyList())).thenReturn(List.of(carol));

        Page<CommentResponse> page = commentService.listTopLevel(POST, 0, 20, NOW);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getReplyCount()).isEqualTo(3);
        assertThat(page.getContent().get(0).getAuthorName()).isEqualTo("Carol");
    }

    @Test
    void authorMissingFallsBackToPlaceholder() {
        Comment top = Comment.create(POST, OTHER, "top", null, NOW);
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(mock(Post.class)));
        when(commentRepository.findByPostIdAndParentCommentIdIsNullAndDeletedFalse(eq(POST), any()))
                .thenReturn(new PageImpl<>(List.of(top), PageRequest.of(0, 20), 1));
        when(commentRepository.countByParentCommentIdAndDeletedFalse(top.getId())).thenReturn(0L);
        when(userRepository.findAllById(anyList())).thenReturn(List.of()); // 作者缺失

        Page<CommentResponse> page = commentService.listTopLevel(POST, 0, 20, NOW);

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
