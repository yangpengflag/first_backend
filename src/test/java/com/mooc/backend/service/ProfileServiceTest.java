package com.mooc.backend.service;
import com.mooc.backend.service.ProfileService;

import com.mooc.backend.entity.User;
import com.mooc.backend.repository.UserRepository;
import com.mooc.backend.exception.AuthException;
import com.mooc.backend.exception.ErrorCode;
import com.mooc.backend.dto.UpdateProfileRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 档案业务逻辑单元测试（Task 2.1）。
 *
 * <p>覆盖：部分更新语义（null 字段跳过）、tags 大小写不敏感去重、avatarUrl 协议白名单、
 * bio 超长拒绝（bean validation 声明）、{@code {}} 幂等、统一以传入 now touch。
 * 实体行为用真实 {@link User}（{@code User.register} 为纯领域方法，无需 Spring 上下文），
 * 仓储用 Mockito 隔离。
 */
@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ProfileService profileService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-28T10:00:00Z");
    private static final Instant LATER = NOW.plus(Duration.ofHours(1));

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private User registeredUser() {
        return User.register("alice@example.com", "$2a$10$hashedpasswordvalue", "Alice", NOW);
    }

    private void givenExistingUser(User user) {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        // save 不 stub：service 忽略其返回值；是否落库由 updateProfilePersistsThroughRepository 以 verify 断言
    }

    // ---------- getProfile ----------

    @Test
    void getProfileMapsEntityFields() {
        User user = registeredUser();
        user.updateProfile(null, "https://cdn.example.com/a.png", "Mountain lover.",
                List.of("Hiking", "Coffee"), NOW);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        var profile = profileService.getProfile(USER_ID);

        assertThat(profile.getId()).isEqualTo(user.getId());
        assertThat(profile.getEmail()).isEqualTo("alice@example.com");
        assertThat(profile.getDisplayName()).isEqualTo("Alice");
        assertThat(profile.getAvatarUrl()).isEqualTo("https://cdn.example.com/a.png");
        assertThat(profile.getBio()).isEqualTo("Mountain lover.");
        assertThat(profile.getTags()).containsExactly("Hiking", "Coffee");
        assertThat(profile.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void getProfileThrowsWhenUserMissing() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> profileService.getProfile(USER_ID))
                .isInstanceOfSatisfying(AuthException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    // ---------- 部分更新语义 ----------

    @Test
    void updateProfileAppliesOnlyProvidedFields() {
        User user = registeredUser();
        givenExistingUser(user);

        profileService.updateProfile(USER_ID, new UpdateProfileRequest(null, null, "hello bio", null), LATER);

        assertThat(user.getBio()).isEqualTo("hello bio");
        // 未传字段保持原值
        assertThat(user.getDisplayName()).isEqualTo("Alice");
        assertThat(user.getAvatarUrl()).isNull();
        assertThat(user.getTags()).isNull();
    }

    @Test
    void updateProfileTouchesOnceWithInjectedNow() {
        User user = registeredUser();
        givenExistingUser(user);

        profileService.updateProfile(USER_ID, new UpdateProfileRequest("Bob", null, null, null), LATER);

        // 统一以传入 now 为准（对齐 BaseEntity.touch 注入约定），而非 setter 的系统时钟
        assertThat(user.getUpdatedAt()).isEqualTo(LATER);
        assertThat(user.getDisplayName()).isEqualTo("Bob");
    }

    @Test
    void updateProfilePersistsThroughRepository() {
        User user = registeredUser();
        givenExistingUser(user);

        profileService.updateProfile(USER_ID, new UpdateProfileRequest("Bob", null, null, null), LATER);

        verify(userRepository).save(user);
    }

    // ---------- displayName ----------

    @Test
    void updateProfileTrimsDisplayName() {
        User user = registeredUser();
        givenExistingUser(user);

        profileService.updateProfile(USER_ID, new UpdateProfileRequest("  Alice Wonder  ", null, null, null), LATER);

        assertThat(user.getDisplayName()).isEqualTo("Alice Wonder");
    }

    @Test
    void updateProfileRejectsBlankDisplayName() {
        User user = registeredUser();
        givenExistingUser(user);

        assertThatThrownBy(() -> profileService.updateProfile(
                USER_ID, new UpdateProfileRequest("   ", null, null, null), LATER))
                .isInstanceOfSatisfying(AuthException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        // 数据不变
        assertThat(user.getDisplayName()).isEqualTo("Alice");
    }

    // ---------- tags ----------

    @Test
    void updateProfileDeduplicatesTagsCaseInsensitively() {
        User user = registeredUser();
        givenExistingUser(user);

        profileService.updateProfile(USER_ID,
                new UpdateProfileRequest(null, null, null, List.of("Hiking", "hiking ", "HIKING")), LATER);

        assertThat(user.getTags()).containsExactly("Hiking");
    }

    @Test
    void updateProfileSkipsBlankTagEntries() {
        User user = registeredUser();
        givenExistingUser(user);

        profileService.updateProfile(USER_ID,
                new UpdateProfileRequest(null, null, null, List.of("Hiking", "   ", "")), LATER);

        assertThat(user.getTags()).containsExactly("Hiking");
    }

    @Test
    void updateProfileRejectsOversizedTag() {
        User user = registeredUser();
        givenExistingUser(user);

        assertThatThrownBy(() -> profileService.updateProfile(USER_ID,
                new UpdateProfileRequest(null, null, null, List.of("x".repeat(31))), LATER))
                .isInstanceOfSatisfying(AuthException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(user.getTags()).isNull();
    }

    @Test
    void updateProfileEmptyTagsListClearsTags() {
        User user = registeredUser();
        user.updateProfile(null, null, null, List.of("Hiking"), NOW);
        givenExistingUser(user);

        profileService.updateProfile(USER_ID,
                new UpdateProfileRequest(null, null, null, List.of()), LATER);

        assertThat(user.getTags()).isEmpty();
    }

    // ---------- avatarUrl 协议白名单 ----------

    @Test
    void updateProfileRejectsJavascriptAvatarUrl() {
        User user = registeredUser();
        givenExistingUser(user);

        assertThatThrownBy(() -> profileService.updateProfile(USER_ID,
                new UpdateProfileRequest(null, "javascript:alert(1)", null, null), LATER))
                .isInstanceOfSatisfying(AuthException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThat(user.getAvatarUrl()).isNull();
    }

    @Test
    void updateProfileRejectsDataAvatarUrl() {
        User user = registeredUser();
        givenExistingUser(user);

        assertThatThrownBy(() -> profileService.updateProfile(USER_ID,
                new UpdateProfileRequest(null, "data:image/png;base64,AAAA", null, null), LATER))
                .isInstanceOfSatisfying(AuthException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void updateProfileAcceptsHttpAndHttpsAvatarUrl() {
        User user = registeredUser();
        givenExistingUser(user);

        profileService.updateProfile(USER_ID,
                new UpdateProfileRequest(null, "https://cdn.example.com/a.png", null, null), LATER);
        assertThat(user.getAvatarUrl()).isEqualTo("https://cdn.example.com/a.png");

        profileService.updateProfile(USER_ID,
                new UpdateProfileRequest(null, "http://cdn.example.com/b.png", null, null), LATER.plusSeconds(1));
        assertThat(user.getAvatarUrl()).isEqualTo("http://cdn.example.com/b.png");
    }

    @Test
    void updateProfileEmptyAvatarUrlClearsIt() {
        User user = registeredUser();
        user.updateProfile(null, "https://cdn.example.com/a.png", null, null, NOW);
        givenExistingUser(user);

        profileService.updateProfile(USER_ID, new UpdateProfileRequest(null, "", null, null), LATER);

        assertThat(user.getAvatarUrl()).isEmpty();
    }

    // ---------- {} 幂等 ----------

    @Test
    void updateProfileEmptyObjectKeepsAllFieldsAndTimestamp() {
        User user = registeredUser();
        user.updateProfile(null, "https://cdn.example.com/a.png", "keep me", List.of("Hiking"), NOW);
        givenExistingUser(user);

        var profile = profileService.updateProfile(USER_ID, new UpdateProfileRequest(null, null, null, null), LATER);

        assertThat(profile.getDisplayName()).isEqualTo("Alice");
        assertThat(profile.getAvatarUrl()).isEqualTo("https://cdn.example.com/a.png");
        assertThat(profile.getBio()).isEqualTo("keep me");
        assertThat(profile.getTags()).containsExactly("Hiking");
        // 空对象：无字段可应用，不 touch（验收只要求字段不变，不 touch 更干净）
        assertThat(user.getUpdatedAt()).isEqualTo(NOW);
    }

    // ---------- 请求校验注解声明（长度上限由 @Valid 在 HTTP 层强制） ----------

    @Test
    void requestValidationRejectsBioOver500Characters() {
        var request = new UpdateProfileRequest(null, null, "x".repeat(501), null);

        var violations = validator.validate(request);

        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("bio"));
    }

    @Test
    void requestValidationRejectsNineTagsAndOversizedAvatarAndDisplayName() {
        var nineTags = new UpdateProfileRequest(null, null, null,
                List.of("a", "b", "c", "d", "e", "f", "g", "h", "i"));
        var oversizedAvatar = new UpdateProfileRequest(null, "https://cdn.example.com/" + "x".repeat(2100), null, null);
        var oversizedDisplayName = new UpdateProfileRequest("x".repeat(31), null, null, null);

        assertThat(validator.validate(nineTags)).anyMatch(v -> v.getPropertyPath().toString().equals("tags"));
        assertThat(validator.validate(oversizedAvatar)).anyMatch(v -> v.getPropertyPath().toString().equals("avatarUrl"));
        assertThat(validator.validate(oversizedDisplayName)).anyMatch(v -> v.getPropertyPath().toString().equals("displayName"));
    }

    @Test
    void requestValidationAcceptsNullFields() {
        var request = new UpdateProfileRequest(null, null, null, null);

        assertThat(validator.validate(request)).isEmpty();
    }
}
