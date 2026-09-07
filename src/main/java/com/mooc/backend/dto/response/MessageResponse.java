package com.mooc.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mooc.backend.dto.response.BaseResponse;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 单条私信出网 DTO（snake_case + request_id，对齐 NotificationResponse 模式）。
 *
 * <p>白名单结构：内容为纯文本字面值；{@code read_at} 为接收方首次已读时刻——
 * 发送者自己的消息恒为 {@code null}（MVP 无 "Seen" 回执 UI）。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class MessageResponse extends BaseResponse {

    @JsonProperty("id")
    private final UUID id;

    @JsonProperty("conversation_id")
    private final UUID conversationId;

    @JsonProperty("sender_id")
    private final UUID senderId;

    @JsonProperty("content")
    private final String content;

    @JsonProperty("read_at")
    private final Instant readAt;

    @JsonProperty("created_at")
    private final Instant createdAt;

    public MessageResponse(UUID id, UUID conversationId, UUID senderId,
                           String content, Instant readAt, Instant createdAt) {
        super();
        this.id = id;
        this.conversationId = conversationId;
        this.senderId = senderId;
        this.content = content;
        this.readAt = readAt;
        this.createdAt = createdAt;
    }

    /** 白名单字段集合，供集成测试断言序列化输出严格等于此集合（含 request_id）。 */
    public static final Set<String> WHITELISTED_FIELDS = Set.of(
            "id", "conversation_id", "sender_id", "content", "read_at", "created_at", "request_id");

    public UUID getId() {
        return id;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public String getContent() {
        return content;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
