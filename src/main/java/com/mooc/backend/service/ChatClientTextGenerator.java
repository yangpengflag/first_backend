package com.mooc.backend.service;

import com.mooc.backend.exception.AiChatUnavailableException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link AiTextGenerator} 的 Spring AI 实现（change: ai-post-assist，tasks 1.2 / design.md D3）。
 *
 * <p>经 {@code ObjectProvider<ChatClient>} 感知装配态：未装配（{@code app.ai-chat.enabled=false}
 * 或 api-key 空 → 不产 ChatClient bean）时直接抛 {@link AiChatUnavailableException}（fail-closed，
 * 实体不出网）。本组件不依赖 ChatClient 直接装配，故总开关关闭时 context 仍正常启动。
 *
 * <p>辅助请求<b>不挂任何工具</b>：纯 {@code .system().user().call().content()} 一次同步调用
 * （round-trip 由 {@code OpenAiApi} 的同步 {@code RestClient} 承担，超时由 {@code AiChatConfig}
 * 的 {@code restClientBuilder} 上界约束）。工具挂载是对话服务 {@code AiChatService} 的每轮职责，
 * 本实现有意不碰工具——写作场景不需要天气 / 汇率 / 景点。
 */
@Component
public class ChatClientTextGenerator implements AiTextGenerator {

    private static final Logger log = LoggerFactory.getLogger(ChatClientTextGenerator.class);

    private final ObjectProvider<ChatClient> chatClientProvider;

    public ChatClientTextGenerator(ObjectProvider<ChatClient> chatClientProvider) {
        this.chatClientProvider = chatClientProvider;
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        ChatClient client = chatClientProvider.getIfAvailable();
        if (client == null) {
            throw new AiChatUnavailableException();
        }
        return client.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
    }
}
