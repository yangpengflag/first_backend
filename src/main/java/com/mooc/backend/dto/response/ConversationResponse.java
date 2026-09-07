package com.mooc.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.dto.response.BaseResponse;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 会话出网 DTO（POST /api/conversations 的会话表示，也是会话列表条目形状；
 * snake_case + request_id，对齐 NotificationResponse 模式）。
 *
 * <p>白名单结构：{@code other_user} 仅 id / display_name / avatar_url。
 * 对方注销（软删）或行缺失时 displayName / avatarUrl 降级为 {@code null}，
 * 条目本身照常返回（spec：对方注销的会话条目保留，身份可降级）。
 * 空会话（可见性延迟窗口内）{@code last_message_preview} / {@code last_message_at} 为 null。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ConversationResponse extends BaseResponse {

    @JsonProperty("id")
    private final UUID id;

    @JsonProperty("other_user")
    private final OtherUser otherUser;

    @JsonProperty("last_message_preview")
    private final String lastMessagePreview;

    @JsonProperty("last_message_at")
    private final Instant lastMessageAt;

    @JsonProperty("unread_count")
    private final long unreadCount;

    /** 对方身份视图；降级态 displayName / avatarUrl 为 null。 */
    public record OtherUser(UUID id,
                            @JsonProperty("display_name") String displayName,
                            @JsonProperty("avatar_url") String avatarUrl) {
    }

    public ConversationResponse(UUID id, OtherUser otherUser, String lastMessagePreview,
                                Instant lastMessageAt, long unreadCount) {
        super();
        this.id = id;
        this.otherUser = otherUser;
        this.lastMessagePreview = lastMessagePreview;
        this.lastMessageAt = lastMessageAt;
        this.unreadCount = unreadCount;
    }

    /** 白名单字段集合，供集成测试断言序列化输出严格等于此集合（含 request_id）。 */
    public static final Set<String> WHITELISTED_FIELDS = Set.of(
            "id", "other_user", "last_message_preview", "last_message_at", "unread_count", "request_id");

    public UUID getId() {
        return id;
    }

    public OtherUser getOtherUser() {
        return otherUser;
    }

    public String getLastMessagePreview() {
        return lastMessagePreview;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public long getUnreadCount() {
        return unreadCount;
    }
}
