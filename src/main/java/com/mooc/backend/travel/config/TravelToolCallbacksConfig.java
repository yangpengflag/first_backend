package com.mooc.backend.travel.config;

import com.mooc.backend.travel.tools.TravelTools;

import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把旅行 {@code @Tool} 方法注册为工具的入口（change: add-travel-services，归档后补记：
 * 为 `ai-chat-core` 接线铺路）。
 *
 * <p><b>为什么需要显式导出</b>：Spring AI 的 {@code ChatClient} 工具装配收集的是上下文中的
 * {@code ToolCallbackProvider} / {@code ToolCallback}，而<b>不会</b>自动扫描任意 {@code @Tool}
 * 注解方法。若 ai-chat-core 落地时只依赖「{@code @Tool} 会被自动发现」的错觉，会出现模型
 * 永远不调工具、直接瞎答。Spring AI 1.1.x 把带 {@code @Tool} 方法的对象转成回调的标准入口是
 * {@link MethodToolCallbackProvider}（1.0 时代的 {@code ToolCallbacks.from} 工具类已不存在，
 * 曾在编译期踩到）。工具仍只定义一次（{@code @Tool} 在方法上），ai-chat-core 建好 ChatClient
 * 即自动获得这两个工具，零感知、零额外接线，满足 spec R11.1「定义一次、同 JVM 直接调用」。
 *
 * <p>MCP over HTTP 传输仍关闭（{@code spring.ai.mcp.server.enabled=false}）；这里与传输无关，
 * 只做同 JVM 的工具注册面。
 */
@Configuration
public class TravelToolCallbacksConfig {

    @Bean
    MethodToolCallbackProvider travelToolCallbacks(TravelTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
