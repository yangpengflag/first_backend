package com.mooc.backend.posts.service;

import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.auth.domain.UserStatus;
import com.mooc.backend.posts.api.CreatePostRequest;
import com.mooc.backend.posts.api.PostResponse;
import com.mooc.backend.posts.api.PostSummary;
import com.mooc.backend.posts.api.UpdatePostRequest;
import com.mooc.backend.posts.domain.Post;
import com.mooc.backend.posts.domain.PostStatus;
import com.mooc.backend.posts.exception.PostException;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    @Mock
    private PostRepository postRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private PostService postService;

    private static final UUID AUTHOR = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");

    @Test
    void createNormalizesTagsAndDerivesSummary() {
        User alice = activeUser(AUTHOR, "Alice", null);
        CreatePostRequest req = new CreatePostRequest("Title", "Hello **world**",
                null, List.of("Hiking ", "SICHUAN"), PostStatus.DRAFT);
        Post saved = Post.create(AUTHOR, "Title", "Hello **world**", null,
                List.of("hiking", "sichuan"), PostStatus.DRAFT, NOW);
        when(postRepository.save(any(Post.class))).thenReturn(saved);
        when(userRepository.findAllById(anyList())).thenReturn(List.of(alice));

        PostResponse resp = postService.create(AUTHOR, req, NOW);

        assertThat(resp.getAuthorId()).isEqualTo(AUTHOR);
        assertThat(resp.getAuthorName()).isEqualTo("Alice");
        assertThat(resp.getTags()).containsExactly("hiking", "sichuan");
        assertThat(resp.getSummary()).isEqualTo("Hello world");
    }

    @Test
    void listPublishedExcludesDraftsAndResolvesAuthor() {
        User alice = activeUser(AUTHOR, "Alice", null);
        Post published = Post.create(AUTHOR, "P", "content here", null, List.of(), PostStatus.PUBLISHED, NOW);
        when(postRepository.findByStatus(eq(PostStatus.PUBLISHED), any()))
                .thenReturn(new PageImpl<>(List.of(published), PageRequest.of(0, 20), 1));
        when(userRepository.findAllById(anyList())).thenReturn(List.of(alice));

        Page<PostSummary> page = postService.listPublished(0, 20, NOW);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getAuthorName()).isEqualTo("Alice");
    }

    @Test
    void sizeClampedToFifty() {
        when(postRepository.findByStatus(eq(PostStatus.PUBLISHED), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

        Page<PostSummary> page = postService.listPublished(0, 200, NOW);

        assertThat(page.getPageable().getPageSize()).isEqualTo(50);
    }

    @Test
    void getPublishedThrowsForDraft() {
        Post draft = Post.create(AUTHOR, "D", "c", null, List.of(), PostStatus.DRAFT, NOW);
        when(postRepository.findById(draft.getId())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> postService.getPublished(draft.getId(), NOW))
                .isInstanceOf(PostException.class);
    }

    @Test
    void updateRejectsNonAuthor() {
        Post post = Post.create(AUTHOR, "T", "c", null, List.of(), PostStatus.DRAFT, NOW);
        when(postRepository.findById(post.getId())).thenReturn(Optional.of(post));

        assertThatThrownBy(() -> postService.update(post.getId(), UUID.randomUUID(),
                new UpdatePostRequest(null, null, null, null, PostStatus.PUBLISHED), NOW))
                .isInstanceOf(PostException.class);
    }

    @Test
    void updatePublishesDraft() {
        User alice = activeUser(AUTHOR, "Alice", null);
        Post post = Post.create(AUTHOR, "T", "c", null, List.of(), PostStatus.DRAFT, NOW);
        when(postRepository.findById(post.getId())).thenReturn(Optional.of(post));
        when(postRepository.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findAllById(anyList())).thenReturn(List.of(alice));

        PostResponse resp = postService.update(post.getId(), AUTHOR,
                new UpdatePostRequest(null, null, null, null, PostStatus.PUBLISHED), NOW);

        assertThat(resp.getStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    void listMineReturnsAllStatuses() {
        User alice = activeUser(AUTHOR, "Alice", null);
        Post draft = Post.create(AUTHOR, "D", "c", null, List.of(), PostStatus.DRAFT, NOW);
        when(postRepository.findByAuthorId(eq(AUTHOR), any()))
                .thenReturn(new PageImpl<>(List.of(draft), PageRequest.of(0, 20), 1));
        when(userRepository.findAllById(anyList())).thenReturn(List.of(alice));

        Page<PostSummary> page = postService.listMine(AUTHOR, 0, 20, NOW);

        assertThat(page.getContent()).hasSize(1);
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
