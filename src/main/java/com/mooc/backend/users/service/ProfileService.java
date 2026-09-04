package com.mooc.backend.users.service;

import com.mooc.backend.auth.domain.User;
import com.mooc.backend.auth.domain.UserRepository;
import com.mooc.backend.auth.exception.AuthException;
import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.users.dto.ProfileResponse;
import com.mooc.backend.users.dto.UpdateProfileRequest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 本人档案业务逻辑。
 *
 * <p>读取直接映射实体；编辑严格遵循 PATCH 部分更新语义——请求中为 {@code null}
 * 的字段不修改，任一字段出现即视为有效编辑并统一以传入 {@code now} 触发一次
 * {@code touch}（空对象 {@code {}} 无字段可应用，幂等且不刷新时间戳）。
 *
 * <p>格式类校验在此实施（validation 注解只做长度）：
 * displayName 非纯空白、avatarUrl 协议白名单（http/https）、tags 单条 ≤30 字符
 * 并做大小写不敏感去重。校验失败抛 {@code VALIDATION_FAILED}，数据库不变。
 *
 * <p>复用 {@code auth} 域的 {@link UserRepository} 与 {@link AuthException}
 * （跨包依赖已被 design 接受）：当前用户不存在属防御性分支——
 * {@code UserStatusFilter} 已对每个携带凭证的请求回查过用户，
 * 走 {@code UNAUTHENTICATED}（401）而非杜撰 404 语义。
 */
@Service
public class ProfileService {

    private static final int MAX_TAG_LENGTH = 30;

    private final UserRepository userRepository;

    public ProfileService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public ProfileResponse getProfile(UUID userId) {
        return ProfileResponse.from(loadUser(userId));
    }

    @Transactional
    public ProfileResponse updateProfile(UUID userId, UpdateProfileRequest request, Instant now) {
        User user = loadUser(userId);

        String displayName = null;
        String avatarUrl = null;
        String bio = null;
        List<String> tags = null;
        boolean changed = false;

        if (request.displayName() != null) {
            String trimmed = request.displayName().trim();
            if (trimmed.isEmpty()) {
                throw validationFailed("displayName: must not be blank");
            }
            displayName = trimmed;
            changed = true;
        }
        if (request.avatarUrl() != null) {
            if (request.avatarUrl().isBlank()) {
                // 空白 URL 统一按清除语义处理
                avatarUrl = "";
            } else {
                requireHttpScheme(request.avatarUrl());
                avatarUrl = request.avatarUrl();
            }
            changed = true;
        }
        if (request.bio() != null) {
            bio = request.bio();
            changed = true;
        }
        if (request.tags() != null) {
            tags = normalizeTags(request.tags());
            changed = true;
        }

        if (!changed) {
            return ProfileResponse.from(user);
        }

        user.updateProfile(displayName, avatarUrl, bio, tags, now);
        userRepository.save(user);
        return ProfileResponse.from(user);
    }

    private User loadUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(AuthException::unauthenticated);
    }

    /** avatarUrl 协议白名单：仅 http / https（{@code javascript:}、{@code data:} 等伪协议一律拒绝）。 */
    private void requireHttpScheme(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw validationFailed("avatarUrl: must be a valid URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw validationFailed("avatarUrl: only http and https schemes are allowed");
        }
    }

    /**
     * tags 归一化：trim 每项 → 过滤空白项 → 拒绝单条超长 →
     * 大小写不敏感去重（保留首次输入形态，顺序稳定）。
     */
    private List<String> normalizeTags(List<String> tags) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (String raw : tags) {
            String trimmed = raw == null ? "" : raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > MAX_TAG_LENGTH) {
                throw validationFailed("tags: each tag must be at most " + MAX_TAG_LENGTH + " characters");
            }
            unique.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
        }
        return List.copyOf(unique.values());
    }

    private AuthException validationFailed(String detail) {
        return AuthException.validationFailed(List.of(detail));
    }
}
