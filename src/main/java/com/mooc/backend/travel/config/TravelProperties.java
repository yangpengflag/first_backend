package com.mooc.backend.travel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 旅行实用工具（汇率 / 天气）配置（change: add-travel-services）。
 *
 * <p>绑定为单个 record 而非散落的 {@code @Value}：这些值成组出现，且要在客户端 / 服务 /
 * 调度三处读到，逐个注入会把同一前缀在多个类里重复；{@code Duration} 解析（{@code 26h} /
 * {@code 5m}）走绑定层也比手写解析可靠。
 *
 * @param exchangeRate  汇率子配置
 * @param weather       天气子配置
 * @param client        出网客户端超时
 * @param refreshLockTtl 多实例抢主锁 TTL。上下两个界都要满足：<b>大于单轮任务最坏耗时</b>
 *                      （天气最坏 22 次调用 × 5s read timeout ≈ 110s），<b>小于最短刷新周期</b>（3h）。
 *                      小于下界则锁在任务跑完前过期、另一实例接着抢到，正是这把锁要防的事。
 */
@ConfigurationProperties(prefix = "app.travel")
public record TravelProperties(
        ExchangeRate exchangeRate,
        Weather weather,
        Client client,
        Duration refreshLockTtl) {

    /**
     * 汇率配置。
     *
     * @param enabled     功能开关。<b>语义为「功能停用」</b>，不同于 {@code app.ranking-cache.enabled}
     *                    的「绕过缓存但结果正确」——关闭后读端点返回空数据。
     * @param base        基准币种（固定 CNY：出网一张表，展示方向由前端取倒数）
     * @param refreshCron 刷新 cron
     * @param softTtl     软过期。<b>必须严格大于刷新周期</b>（24h + 2h 余量 = 26h），
     *                    取等或更小会让数据在每个健康周期末尾都被标成过期，标记随即失去信噪比。
     * @param hardTtl     硬过期，超过则返回空数据；同时用作 Redis key 的物理 TTL
     */
    public record ExchangeRate(boolean enabled, String base, String refreshCron,
                               Duration softTtl, Duration hardTtl) {
    }

    /**
     * 天气配置。
     *
     * @param enabled     功能开关
     * @param apiKey      OpenWeatherMap key。<b>空值即哨兵</b>：不装配外部客户端、不触发刷新、
     *                    读端点天气字段返回 null（同 {@code SmtpMailSender} / {@code LoggingMailSender} 套路）。
     *                    经环境变量注入，禁止写入本仓任何文件。
     * @param refreshCron 刷新 cron
     * @param lang        上游 description 语言。显式给值而非依赖上游默认，避免上游改默认导致语言漂移。
     * @param softTtl     软过期（3h 周期 + 1h 余量 = 4h）
     * @param hardTtl     硬过期
     */
    public record Weather(boolean enabled, String apiKey, String refreshCron, String lang,
                          Duration softTtl, Duration hardTtl) {

        /** 天气客户端是否应装配：开关为开且 key 非空白。 */
        public boolean configured() {
            return enabled && apiKey != null && !apiKey.isBlank();
        }
    }

    /**
     * 出网客户端超时。全仓首个出网客户端，两个超时都显式设定——不设则 JDK 默认可能是无限等待，
     * 一个挂住的上游会占满调度线程。
     *
     * @param connectTimeout 连接超时
     * @param readTimeout    读超时（同时是锁 TTL 下界推算的输入：22 次 × readTimeout ≈ 最坏单轮耗时）
     */
    public record Client(Duration connectTimeout, Duration readTimeout) {
    }
}
