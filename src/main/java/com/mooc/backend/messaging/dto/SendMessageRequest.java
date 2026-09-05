package com.mooc.backend.messaging.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 发送私信请求（请求体 camelCase record）。
 *
 * <p>内容规则（spec：发送消息）为 trim 后非空、≤{@link #MAX_CONTENT_LENGTH} 字符、
 * 纯文本按字面处理——trim 与上限校验在 {@code MessagingService} 内以业务校验完成
 * （2001 字符 / 纯空格等用例由服务层测试与集成测试共同覆盖），
 * 此处仅做非空引用的结构性约束。
 */
public record SendMessageRequest(@NotNull String content) {

    /** 消息内容业务上限（trim 后字符数）。 */
    public static final int MAX_CONTENT_LENGTH = 2000;
}
