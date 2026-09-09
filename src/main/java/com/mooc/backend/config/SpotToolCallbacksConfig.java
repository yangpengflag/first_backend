package com.mooc.backend.config;

import com.mooc.backend.service.SpotTools;

import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把景点查询的 {@code @Tool} 方法注册为工具回调（change: ai-spot-tools）。
 *
 * <p><b>为什么是第二个 {@code MethodToolCallbackProvider} bean</b>：Spring AI 的
 * {@code ChatClient} 消费的是上下文中的 {@code ToolCallback} / {@code ToolCallbackProvider}，
 * 不会自动扫描 {@code @Tool} 方法（同 {@code TravelToolCallbacksConfig} 里的教训）。工具仍只定义
 * 一次（{@code @Tool} 在 {@link SpotTools} 的方法上），此处只负责注册。
 *
 * <p>注册第二个 Provider 之所以安全，是因为 {@code AiChatConfig} 不再用
 * {@code ObjectProvider#getIfAvailable()} 取"唯一"提供者并挂成 client 级默认——那会在多候选时抛
 * {@code NoUniqueBeanDefinitionException} 让 context 起不来（spike 实证见 design.md D1）。
 * 现在由 {@code AiChatService} 每轮经 {@code orderedStream()} 收集全部提供者并
 * {@code toolCallbacks(...)} 挂载，多 Provider 天然共存。
 *
 * <p>MCP over HTTP 传输仍关闭（{@code spring.ai.mcp.server.enabled=false}）；这里与传输无关，
 * 只做同 JVM 的工具注册面。
 */
@Configuration
public class SpotToolCallbacksConfig {

    @Bean
    MethodToolCallbackProvider spotToolCallbacks(SpotTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
