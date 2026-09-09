package com.mooc.backend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI 助手 RAG 知识库装配（change: ai-rag，向量库现为 Milvus）。仅注册 {@link AiRagProperties}；
 * 向量库（Milvus + embedding）经 {@code service.rag.KnowledgeStore} 惰性构建
 * （首用才联网，宕机只降级不崩，见 ai-rag design.md D3）。
 */
@Configuration
@EnableConfigurationProperties(AiRagProperties.class)
public class AiRagConfig {
}
