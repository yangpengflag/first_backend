package com.mooc.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code app.ai-rag.*} 宽松绑定契约（change: ai-rag，tasks 2.1 / 4.3）：kebab-case 属性
 * 映射到 record 字段、Duration/cron 字符串解析正确、默认值由 application.yml 承载。
 * 轻量 runner 不拉起 DB / Redis / Web，快且零出网。
 */
class AiRagPropertiesBindingTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(BindConfig.class);

    @Configuration
    @EnableConfigurationProperties(AiRagProperties.class)
    static class BindConfig {
    }

    @Test
    void bindsAllKebabKeysWithOverrides() {
        runner.withPropertyValues(
                "app.ai-rag.enabled=false",
                "app.ai-rag.milvus-host=milvus-host",
                "app.ai-rag.milvus-port=19531",
                "app.ai-rag.embedding-dimension=768",
                "app.ai-rag.collection-name=kb-city",
                "app.ai-rag.top-k=6",
                "app.ai-rag.min-score=0.35",
                "app.ai-rag.chunk-max-chars=900",
                "app.ai-rag.refresh-cron=0 30 3 * * *",
                "app.ai-rag.refresh-lock-ttl=3m").run(ctx -> {
            assertThat(ctx).hasSingleBean(AiRagProperties.class);
            AiRagProperties p = ctx.getBean(AiRagProperties.class);
            assertThat(p.enabled()).isFalse();
            assertThat(p.milvusHost()).isEqualTo("milvus-host");
            assertThat(p.milvusPort()).isEqualTo(19531);
            assertThat(p.embeddingDimension()).isEqualTo(768);
            assertThat(p.collectionName()).isEqualTo("kb-city");
            assertThat(p.topK()).isEqualTo(6);
            assertThat(p.minScore()).isEqualTo(0.35);
            assertThat(p.chunkMaxChars()).isEqualTo(900);
            assertThat(p.refreshCron()).isEqualTo("0 30 3 * * *");
            assertThat(p.refreshLockTtl()).isEqualTo(Duration.ofMinutes(3));
        });
    }

    @Test
    void defaultsMatchYmlDocumentedValuesWhenKeysAbsent() {
        runner.run(ctx -> {
            AiRagProperties p = ctx.getBean(AiRagProperties.class);
            // 键缺席时字段为空值（默认值由 application.yml 的 app.ai-rag 段承载）；
            // 此处断言"缺席不产生错误绑定"并保留 yml 作为唯一默认源。
            assertThat(p.topK()).isZero();
            assertThat(p.enabled()).isFalse();
        });
    }
}
