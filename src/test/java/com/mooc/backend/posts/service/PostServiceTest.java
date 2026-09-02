package com.mooc.backend.posts.service;

import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.auth.domain.UserStatus;
import com.mooc.backend.posts.api.CreatePostRequest;
import com.mooc.backend.posts.api.PostListResponse;
import com.mooc.backend.posts.api.PostResponse;
import com.mooc.backend.posts.api.PostStatsView;
import com.mooc.backend.posts.api.PostSummary;
import com.mooc.backend.posts.api.UpdatePostRequest;
import com.mooc.backend.posts.domain.Post;
import com.mooc.backend.posts.domain.PostStatus;
import com.mooc.backend.posts.exception.PostException;
import com.mooc.backend.posts.repository.PostRepository;
import com.mooc.backend.posts.repository.PostSort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
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
                null, List.of("Hiking ", "SICHUAN"), PostStatus.DRAFT, null, null);
        Post saved = Post.create(AUTHOR, "Title", "Hello **world**", null,
                List.of("hiking", "sichuan"), PostStatus.DRAFT, null, List.of(), NOW);
        when(postRepository.save(any(Post.class))).thenReturn(saved);
        when(userRepository.findAllById(anyList())).thenReturn(List.of(alice));

        PostResponse resp = postService.create(AUTHOR, req, NOW);

        assertThat(resp.getAuthorId()).isEqualTo(AUTHOR);
        assertThat(resp.getAuthorName()).isEqualTo("Alice");
        assertThat(resp.getTags()).containsExactly("hiking", "sichuan");
        assertThat(resp.getSummary()).isEqualTo("Hello world");
    }

    @Test
    void listPublishedExcludesDraftsAndResolvesAuthorWithStats() {
        User alice = activeUser(AUTHOR, "Alice", null);
        UUID postId = UUID.randomUUID();
        Post published = Post.create(AUTHOR, "P", "content here", null, List.of(), PostStatus.PUBLISHED, null, List.of(), NOW);
        setId(published, postId);
        PostStatsView stat = new PostStatsView(postId, 3L, 6L, 2L);
        when(postRepository.findPublishedStats(any(), anyInt(), anyInt(), any(), any(), anyBoolean()))
                .thenReturn(List.of(stat));
        when(postRepository.findAllById(anyList())).thenReturn(List.of(published));
        when(userRepository.findAllById(anyList())).thenReturn(List.of(alice));
        when(postRepository.countByStatusAndDeletedFalse(any())).thenReturn(1L);

        PostListResponse resp = postService.listPublished(null, null, 1, 20, NOW);

        assertThat(resp.getItems()).hasSize(1);
        PostSummary item = resp.getItems().get(0);
        assertThat(item.getAuthorName()).isEqualTo("Alice");
        assertThat(item.getCommentCount()).isEqualTo(3);
        assertThat(item.getUpVoteCount()).isEqualTo(6);
        assertThat(item.getBookmarkCount()).isEqualTo(2);
    }

    @Test
    void sizeClampedToHundred() {
        when(postRepository.findPublishedStats(any(), anyInt(), anyInt(), any(), any(), anyBoolean()))
                .thenReturn(List.of());

        PostListResponse resp = postService.listPublished("top", null, 1, 200, NOW);

        assertThat(resp.getSize()).isEqualTo(100);
    }

    @Test
    void getPublishedThrowsForDraft() {
        Post draft = Post.create(AUTHOR, "D", "c", null, List.of(), PostStatus.DRAFT, null, List.of(), NOW);
        when(postRepository.findByIdAndDeletedFalse(draft.getId())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> postService.getPublished(draft.getId(), NOW))
                .isInstanceOf(PostException.class);
    }

    @Test
    void updateRejectsNonAuthor() {
        Post post = Post.create(AUTHOR, "T", "c", null, List.of(), PostStatus.DRAFT, null, List.of(), NOW);
        when(postRepository.findByIdAndDeletedFalse(post.getId())).thenReturn(Optional.of(post));

        assertThatThrownBy(() -> postService.update(post.getId(), UUID.randomUUID(),
                new UpdatePostRequest(null, null, null, null, PostStatus.PUBLISHED, null, null), NOW))
                .isInstanceOf(PostException.class);
    }

    @Test
    void updatePublishesDraft() {
        User alice = activeUser(AUTHOR, "Alice", null);
        Post post = Post.create(AUTHOR, "T", "c", null, List.of(), PostStatus.DRAFT, null, List.of(), NOW);
        when(postRepository.findByIdAndDeletedFalse(post.getId())).thenReturn(Optional.of(post));
        when(postRepository.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findAllById(anyList())).thenReturn(List.of(alice));

        PostResponse resp = postService.update(post.getId(), AUTHOR,
                new UpdatePostRequest(null, null, null, null, PostStatus.PUBLISHED, null, null), NOW);

        assertThat(resp.getStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    void deleteRejectsNonAuthor() {
        Post post = Post.create(AUTHOR, "T", "c", null, List.of(), PostStatus.DRAFT, null, List.of(), NOW);
        when(postRepository.findByIdAndDeletedFalse(post.getId())).thenReturn(Optional.of(post));

        assertThatThrownBy(() -> postService.delete(post.getId(), UUID.randomUUID(), NOW))
                .isInstanceOf(PostException.class);
    }

    @Test
    void deleteSoftRemovesPost() {
        Post post = Post.create(AUTHOR, "T", "c", null, List.of(), PostStatus.PUBLISHED, null, List.of(), NOW);
        when(postRepository.findByIdAndDeletedFalse(post.getId())).thenReturn(Optional.of(post));
        when(postRepository.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));

        postService.delete(post.getId(), AUTHOR, NOW);

        assertThat(post.isDeleted()).isTrue();
    }

    @Test
    void listMineReturnsAllStatusesWithStats() {
        User alice = activeUser(AUTHOR, "Alice", null);
        UUID postId = UUID.randomUUID();
        Post draft = Post.create(AUTHOR, "D", "c", null, List.of(), PostStatus.DRAFT, null, List.of(), NOW);
        setId(draft, postId);
        PostStatsView stat = new PostStatsView(postId, 0L, 0L, 0L);
        when(postRepository.findMyStats(any(), any(), anyInt(), anyInt(), any(), any(), anyBoolean()))
                .thenReturn(List.of(stat));
        when(postRepository.findAllById(anyList())).thenReturn(List.of(draft));
        when(userRepository.findAllById(anyList())).thenReturn(List.of(alice));
        when(postRepository.countByAuthorIdAndDeletedFalse(any())).thenReturn(1L);

        PostListResponse resp = postService.listMine(AUTHOR, null, null, 1, 20, NOW);

        assertThat(resp.getItems()).hasSize(1);
        assertThat(resp.getItems().get(0).getAuthorName()).isEqualTo("Alice");
    }

    @Test
    void listPublishedOffsetReportsHasMoreAndNullCursor() {
        Post published = Post.create(AUTHOR, "P", "c", null, List.of(), PostStatus.PUBLISHED, null, List.of(), NOW);
        UUID postId = UUID.randomUUID();
        setId(published, postId);
        PostStatsView stat = new PostStatsView(postId, 1L, 1L, 1L);
        when(postRepository.findPublishedStats(any(), anyInt(), anyInt(), any(), any(), anyBoolean()))
                .thenReturn(List.of(stat));
        when(postRepository.findAllById(anyList())).thenReturn(List.of(published));
        when(postRepository.countByStatusAndDeletedFalse(any())).thenReturn(25L);

        PostListResponse first = postService.listPublished("top", null, 1, 20, NOW);
        assertThat(first.isHasMore()).isTrue();
        assertThat(first.getNextCursor()).isNull();
        assertThat(first.getPage()).isEqualTo(1);
        assertThat(first.getTotal()).isEqualTo(25);

        PostListResponse last = postService.listPublished("top", null, 2, 20, NOW);
        assertThat(last.isHasMore()).isFalse();
        assertThat(last.getNextCursor()).isNull();
    }

    @Test
    void listMineOffsetReportsHasMore() {
        Post draft = Post.create(AUTHOR, "D", "c", null, List.of(), PostStatus.DRAFT, null, List.of(), NOW);
        UUID postId = UUID.randomUUID();
        setId(draft, postId);
        PostStatsView stat = new PostStatsView(postId, 1L, 1L, 1L);
        when(postRepository.findMyStats(any(), any(), anyInt(), anyInt(), any(), any(), anyBoolean()))
                .thenReturn(List.of(stat));
        when(postRepository.findAllById(anyList())).thenReturn(List.of(draft));
        when(postRepository.countByAuthorIdAndDeletedFalse(any())).thenReturn(25L);

        PostListResponse first = postService.listMine(AUTHOR, "most_commented", null, 1, 20, NOW);
        assertThat(first.isHasMore()).isTrue();
        assertThat(first.getNextCursor()).isNull();

        PostListResponse last = postService.listMine(AUTHOR, "most_commented", null, 2, 20, NOW);
        assertThat(last.isHasMore()).isFalse();
    }

    private User activeUser(UUID id, String name, String avatar) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.getDisplayName()).thenReturn(name);
        when(user.getAvatarUrl()).thenReturn(avatar);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        return user;
    }

    /** Post 未暴露 setId，测试用反射设定 id 以匹配聚合视图的 postId。 */
    private void setId(Post post, UUID id) {
        try {
            var field = post.getClass().getSuperclass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(post, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
