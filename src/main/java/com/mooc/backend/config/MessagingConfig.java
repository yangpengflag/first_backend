package com.mooc.backend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 私信模块装配（Task 2.3 / 3.2）。
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingConfig {
}
