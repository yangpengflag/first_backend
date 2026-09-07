package com.mooc.backend.ai.domain;

/**
 * 聊天消息角色（change: ai-chat-core）。
 */
public enum ChatMessageRole {
    /** 用户消息（请求前先落库，保证生成失败用户的话也不丢）。 */
    USER,
    /** 助手消息（完整回答生成结束后落库，非逐 token）。 */
    ASSISTANT
}
