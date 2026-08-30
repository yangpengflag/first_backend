package com.mooc.backend.bookmarks.service;

import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.auth.domain.UserStatus;
import com.mooc.backend.bookmarks.api.BookmarkResponse;
import com.mooc.backend.bookmarks.api.BookmarkSummary;
import com.mooc.backend.bookmarks.domain.Bookmark;
import com.mooc.backend.bookmarks.exception.BookmarkException;
import com.mooc.backend.bookmarks.repository.BookmarkRepository;
import com.mooc.backend.posts.domain.Post;
import com.mooc.backend.posts.domain.PostStatus;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookmarkServiceTest {

    @Mock
    private BookmarkRepository bookmarkRepository;

    @Mock
    private PostRepository postRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private BookmarkService bookmarkService;

    private static final UUID POST = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");

    @Test
    void toggleCreatesWhenNotExists() {
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(mock(Post.class)));
        when(bookmarkRepository.findByPostIdAndUserId(POST, USER)).thenReturn(Optional.empty());
        when(bookmarkRepository.save(any(Bookmark.class))).thenAnswer(inv -> inv.getArgument(0));

        BookmarkResponse resp = bookmarkService.toggle(POST, USER, NOW);

        assertThat(resp.getPostId()).isEqualTo(POST);
        assertThat(resp.isBookmarked()).isTrue();
    }

    @Test
    void toggleCancelsWhenExists() {
        Bookmark existing = Bookmark.create(POST, USER, NOW);
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(mock(Post.class)));
        when(bookmarkRepository.findByPostIdAndUserId(POST, USER)).thenReturn(Optional.of(existing));

        BookmarkResponse resp = bookmarkService.toggle(POST, USER, NOW);

        assertThat(resp.isBookmarked()).isFalse();
        verify(bookmarkRepository).delete(existing);
    }

    @Test
    void togglePostNotFound() {
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookmarkService.toggle(POST, USER, NOW))
                .isInstanceOf(BookmarkException.class);
    }

    @Test
    void listShowsAllIncludingUnavailablePlaceholder() {
        UUID livePost = UUID.randomUUID();
        UUID gonePost = UUID.randomUUID();
        Bookmark b1 = Bookmark.create(livePost, USER, NOW);
        Bookmark b2 = Bookmark.create(gonePost, USER, NOW);

        Post live = mock(Post.class);
        when(live.getId()).thenReturn(livePost);
        when(live.getStatus()).thenReturn(PostStatus.PUBLISHED);
        when(live.isDeleted()).thenReturn(false);
        when(live.getAuthorId()).thenReturn(USER);
        when(live.getContent()).thenReturn("content");

        when(bookmarkRepository.findByUserIdOrderByCreatedAtDesc(any(UUID.class), any()))
                .thenReturn(new PageImpl<>(List.of(b1, b2), PageRequest.of(0, 20), 2));
        // gonePost 在 findAllById 结果中缺失 → available=false
        when(postRepository.findAllById(anyList())).thenReturn(List.of(live));
        User alice = activeUser(USER, "Alice", null);
        when(userRepository.findAllById(anyList())).thenReturn(List.of(alice));

        Page<BookmarkSummary> page = bookmarkService.listBookmarks(USER, 0, 20, NOW);

        assertThat(page.getContent()).hasSize(2);
        BookmarkSummary liveItem = page.getContent().get(0);
        assertThat(liveItem.isAvailable()).isTrue();
        assertThat(liveItem.getPost()).isNotNull();
        BookmarkSummary goneItem = page.getContent().get(1);
        assertThat(goneItem.isAvailable()).isFalse();
        assertThat(goneItem.getPost()).isNull();
    }

    @Test
    void isBookmarkedTrueWhenExists() {
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(mock(Post.class)));
        when(bookmarkRepository.findByPostIdAndUserId(POST, USER)).thenReturn(Optional.of(Bookmark.create(POST, USER, NOW)));

        assertThat(bookmarkService.isBookmarked(POST, USER)).isTrue();
    }

    @Test
    void isBookmarkedFalseWhenNotExists() {
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.of(mock(Post.class)));
        when(bookmarkRepository.findByPostIdAndUserId(POST, USER)).thenReturn(Optional.empty());

        assertThat(bookmarkService.isBookmarked(POST, USER)).isFalse();
    }

    @Test
    void isBookmarkedPostNotFound() {
        when(postRepository.findByIdAndDeletedFalse(POST)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookmarkService.isBookmarked(POST, USER))
                .isInstanceOf(BookmarkException.class);
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
