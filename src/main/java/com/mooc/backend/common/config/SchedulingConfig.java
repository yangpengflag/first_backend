package com.mooc.backend.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 全仓定时调度总开关（change: add-travel-services 首次引入）。
 *
 * <p>放在 {@code common} 而非 {@code travel} 之下是有意的：调度是跨模块基建，
 * {@code 2026-08-31-places-ingestion} 的 design 同样假设了它。后续 change 直接写
 * {@code @Scheduled} 即可，<b>不要各自再挂一个 {@code @EnableScheduling}</b>——多处启用
 * 本身无害，但会让「调度池大小配在哪、谁负责」失去唯一答案。
 *
 * <p>池大小见 {@code application.yml} 的 {@code spring.task.scheduling.pool.size}：默认值是
 * <b>1</b>，而本模块启动时会向同一个池投递一次预热任务，天气预热最坏约 110s；池为 1 时
 * cron 触发的汇率任务会排在它后面。
 *
 * <p>不启用 {@code @EnableAsync}：预热改道用的是 {@code TaskScheduler}（同一个调度池），
 * 没有第二种线程池要配，也就不需要第二个注解。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
