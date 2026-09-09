package com.mooc.backend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 搜索模块配置装配（change: ai-semantic-search）。照 AiRag / Travel 各模块惯例：
 * {@code @EnableConfigurationProperties} 显式注册，不给 {@code BackendApplication} 加全仓扫描。
 */
@Configuration
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfig {
}
