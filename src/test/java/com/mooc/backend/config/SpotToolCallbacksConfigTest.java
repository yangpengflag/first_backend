package com.mooc.backend.config;

import com.mooc.backend.repository.CityRepository;
import com.mooc.backend.service.SpotService;
import com.mooc.backend.service.SpotTools;

import org.junit.jupiter.api.Test;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 景点工具注册回归（change: ai-spot-tools，task 2.2 / 2.3）：确认 {@link SpotToolCallbacksConfig}
 * 把两个 {@code @Tool} 方法经 {@link MethodToolCallbackProvider} 注册出来，工具名固定，
 * 且未配置模型 key（对话模型不装配）时工具 bean 依然存在、不产生任何外部调用。
 */
class SpotToolCallbacksConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SpotToolCallbacksConfig.class)
            .withBean(SpotTools.class, () -> new SpotTools(mock(SpotService.class),
                    mock(CityRepository.class)));

    @Test
    void registersTheTwoPinnedSpotTools() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(MethodToolCallbackProvider.class);
            ToolCallbackProvider provider = ctx.getBean(MethodToolCallbackProvider.class);
            Set<String> toolNames = Stream.of(provider.getToolCallbacks())
                    .map(ToolCallback::getToolDefinition)
                    .map(td -> td.name())
                    .collect(Collectors.toSet());
            assertThat(toolNames).isEqualTo(Set.of("search_spots", "get_spot_details"));
        });
    }

    @Test
    void toolsAreRegisteredEvenWhenNoModelIsConfigured() {
        runner.withPropertyValues("app.ai-chat.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    // 工具是纯只读 DB 查询，不依赖模型装配；无 key 时 bean 仍在（只是没有消费者）
                    assertThat(ctx.getBean(SpotTools.class)).isNotNull();
                    assertThat(ctx.getBeansOfType(ToolCallbackProvider.class)).hasSize(1);
                });
    }
}
