package com.mooc.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * AI 助手 RAG 知识库配置（change: ai-rag，向量库现为 Milvus）。前缀 {@code app.ai-rag}，经
 * {@code @EnableConfigurationProperties} 注册（照 Travel / AiChat 各模块惯例）。
 *
 * <p>默认值语义与 travel / ai-chat 一致：默认值写在 application.yml（本 record 不设默认），
 * {@code enabled=false} 是「功能停用」kill switch——对话照常但无知识注入。
 *
 * @param enabled            功能开关：false 时知识检索 / 索引整体缺席（对话走纯模型路径）
 * @param milvusHost         Milvus 向量库 gRPC 主机（进程外服务，默认 localhost）
 * @param milvusPort         Milvus 向量库 gRPC 端口（Standalone 默认 19530）
 * @param embeddingDimension 向量集合维度——与 embedding 模型绑定（DashScope text-embedding-v3 = 1024），
 *                           Milvus 建集合时 schema 固定，必须显式指定
 * @param collectionName     向量集合名
 * @param topK               每轮检索取相似度最高条数（注入上界 = chunkMaxChars × topK）
 * @param minScore           相似度下限；默认 0（语料小不设阈值，靠模型判断过滤）
 * @param chunkMaxChars      单块字符上限（切块器使用）
 * @param refreshCron        知识库重建 cron
 * @param refreshLockTtl     多实例重建锁 TTL
 */
@ConfigurationProperties(prefix = "app.ai-rag")
public record AiRagProperties(
        boolean enabled,
        String milvusHost,
        int milvusPort,
        int embeddingDimension,
        String collectionName,
        int topK,
        double minScore,
        int chunkMaxChars,
        String refreshCron,
        Duration refreshLockTtl) {
}
