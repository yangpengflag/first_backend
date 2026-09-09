package com.mooc.backend.config;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具提供者的装配回归（change: ai-spot-tools，D1）。
 *
 * <p><b>背景</b>：改造前 {@code ChatClient} 在装配期用
 * {@code ObjectProvider<MethodToolCallbackProvider>#getIfAvailable()} 取唯一的工具提供者并挂成
 * client 级默认。spike 实证（见 design.md D1）：注册第二个 {@code MethodToolCallbackProvider}
 * 时该调用抛 {@code NoUniqueBeanDefinitionException}（"expected single matching bean but found 2"），
 * 导致 {@code aiChatClient} bean 创建失败 → <b>context fail-START</b>。
 *
 * <p><b>改造后</b>：{@code ChatClient} 不再持有默认工具，改由 {@code AiChatService} 每轮按请求挂载
 * （{@code ObjectProvider#orderedStream()} + {@code toolCallbacks(ToolCallbackProvider...)}）。
 * 本用例锁定：多提供者共存时装配<b>必须成功</b>——这是"后续加任何新工具都不必再动装配结构"的护栏。
 *
 * <p>用 {@code ApplicationContextRunner} 只装配 {@code AiChatConfig} 与工具提供者：不加载整个应用、
 * 不出网（只建 bean，不发请求）。
 */
class AiChatToolProvidersAssemblyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiChatConfig.class)
            .withPropertyValues("app.ai-chat.enabled=true", "spring.ai.openai.api-key=spike-dummy");

    @Test
    void singleToolProviderStillAssembles() {
        runner.withUserConfiguration(OneProviderConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ChatClient.class)).isNotNull();
                    assertThat(context.getBeansOfType(ToolCallbackProvider.class)).hasSize(1);
                });
    }

    @Test
    void twoToolProvidersCoexistWithoutBreakingAssembly() {
        runner.withUserConfiguration(TwoProvidersConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // 回归点：改造前这里会 BeanCreationException（NoUniqueBeanDefinitionException）
                    assertThat(context.getBean(ChatClient.class)).isNotNull();
                    assertThat(context.getBeansOfType(ToolCallbackProvider.class)).hasSize(2);
                });
    }

    @Test
    void noToolProviderStillAssemblesChatClient() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ChatClient.class)).isNotNull();
            assertThat(context.getBeansOfType(ToolCallbackProvider.class)).isEmpty();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class OneProviderConfig {

        @Bean
        MethodToolCallbackProvider providerOne() {
            return MethodToolCallbackProvider.builder().toolObjects(new ToolA()).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoProvidersConfig {

        @Bean
        MethodToolCallbackProvider providerOne() {
            return MethodToolCallbackProvider.builder().toolObjects(new ToolA()).build();
        }

        @Bean
        MethodToolCallbackProvider providerTwo() {
            return MethodToolCallbackProvider.builder().toolObjects(new ToolB()).build();
        }
    }

    static class ToolA {

        @Tool(name = "tool_a", description = "Assembly test tool A.")
        public String a() {
            return "a";
        }
    }

    static class ToolB {

        @Tool(name = "tool_b", description = "Assembly test tool B.")
        public String b() {
            return "b";
        }
    }
}
