package com.mooc.backend.ai.service;

import com.mooc.backend.ai.exception.AiChatUnavailableException;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import reactor.core.publisher.Flux;

/**
 * AI 行程助手核心服务（change: ai-chat-core）。
 *
 * <p>装配态由 {@link ObjectProvider}{@code <ChatClient>} 表达：模型凭据未配置或功能开关
 * 关闭时容器内没有 ChatClient bean，{@code configured()} 为 false，对话请求直接产出降级错误
 * （fail-closed，spec「模型装配与缺凭据显式降级」），绝不触碰任何模型调用。
 *
 * <p>完整对话管线（多轮上下文组装、工具调用、流式增量）在任务 3.1 填充；
 * 本类当前先锁定装配态契约（任务 1.3）。
 */
public class AiChatService {

    private final ObjectProvider<ChatClient> chatClientProvider;

    public AiChatService(ObjectProvider<ChatClient> chatClientProvider) {
        this.chatClientProvider = chatClientProvider;
    }

    /** 模型是否已装配（容器中存在 ChatClient bean）。 */
    public boolean configured() {
        return chatClientProvider.getIfAvailable() != null;
    }

    /**
     * 单轮对话流式入口。未装配时立即返回降级错误（不经过任何模型/网络层）。
     *
     * @param sessionId 会话标识（客户端生成的 UUID）
     * @param message   本轮用户消息
     * @return 助手回答的增量内容流
     */
    public Flux<String> stream(String sessionId, String message) {
        if (!configured()) {
            return Flux.error(new AiChatUnavailableException());
        }
        return doStream(chatClientProvider.getObject(), sessionId, message);
    }

    /** 装配态下的真实生成管线：多轮上下文 + 工具 + 流式——任务 3.1 填充。 */
    private Flux<String> doStream(ChatClient chatClient, String sessionId, String message) {
        // TODO(task 3.1): load recent messages, assemble prompt, chatClient.prompt()...stream()
        throw new UnsupportedOperationException("stream pipeline lands in task 3.1");
    }
}
