package com.mooc.backend.service;

import com.mooc.backend.service.WeatherClient;
import com.mooc.backend.service.WeatherFetchResult;
import com.mooc.backend.config.TravelProperties;
import com.mooc.backend.service.WeatherCityQueries;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 天气批量刷新任务（change: add-travel-services，task 4.4）。本类是天气数据<b>唯一的出网路径</b>。
 *
 * <p>每轮：抢锁 → 遍历 {@link WeatherCityQueries} 的 11 个策展 slug → 逐城拉取并落 Redis。
 * 与汇率任务的差异：一轮<b>多次出网</b>，故失败处置是<b>逐城、分型</b>的——
 *
 * <ul>
 *   <li><b>单城失败不中断批量</b>（spec）：某城 429 不该让其余 10 城也空窗；</li>
 *   <li><b>凭据被拒（401/403）单独告警且每轮至多一条</b>：11 城共用同一把 key，逐城告警是
 *       11 条同文刷屏；告警指名 {@code OPENWEATHER_API_KEY}（可行动），且<b>不与瞬时故障共用
 *       节流窗口</b>——瞬时故障等下个 tick 自愈，凭据被拒不会自愈，必须有人改配置，两者性质
 *       不同不该互相抑制；</li>
 *   <li><b>瞬时故障（429 / 超时 / 5xx）按节流窗口告警</b>：保留旧值，等下个 tick 自然重试。</li>
 * </ul>
 *
 * <p><b>日志卫生</b>：任何分支只记 slug / 状态码 / 异常类名，绝不记异常消息——RestClient 异常
 * 消息含完整 URL（带 {@code appid} 凭据），透传即泄漏（WeatherClientTest 同一条约束）。
 *
 * <p><b>跑完不释放锁</b>（design §4）：锁 TTL 的第二个作用是「两轮之间的最小间隔」。
 * 预热由 {@link TravelWarmUpListener} 自动覆盖（实现 {@link TravelRefreshJob} 即入列）。
 */
@Component
public class WeatherRefreshJob implements TravelRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(WeatherRefreshJob.class);

    /** 锁名（design §4 的 {@code travel:refresh-lock:{job}}，job ∈ {rates, weather}）。 */
    private static final String JOB_NAME = "weather";

    /** 瞬时故障告警节流窗口，照 {@code RankingCacheService} / {@code WeatherService}。 */
    private static final long WARN_INTERVAL_MS = 10_000;

    private final ObjectProvider<WeatherClient> clientProvider;
    private final WeatherService service;
    private final RefreshLock lock;
    private final TravelProperties.Weather config;
    private volatile long lastTransientWarnMs;

    WeatherRefreshJob(ObjectProvider<WeatherClient> clientProvider, WeatherService service,
                      RefreshLock lock, TravelProperties props) {
        this.clientProvider = clientProvider;
        this.service = service;
        this.lock = lock;
        this.config = props.weather();
    }

    @Override
    public String jobName() {
        return JOB_NAME;
    }

    /** cron 入口。表达式走占位符——写死字面量会让 yml 里的 {@code refresh-cron} 变成装饰。 */
    @Scheduled(cron = "${app.travel.weather.refresh-cron}")
    void scheduledRefresh() {
        refresh();
    }

    @Override
    public void refresh() {
        WeatherClient client = clientProvider.getIfAvailable();
        if (!config.enabled() || client == null) {
            return; // key 空哨兵：客户端未装配，彻底静默，连锁都不抢（抢了会挡住启用的实例）
        }
        if (!lock.tryAcquire(JOB_NAME)) {
            log.debug("[travel] weather refresh skipped (another instance holds the lock)");
            return;
        }
        boolean credentialAlerted = false;
        for (String slug : WeatherCityQueries.slugs()) {
            // slugs() 与 queryFor 同源，orElseThrow 结构上不可达；显式判空只为不靠假设
            String query = WeatherCityQueries.queryFor(slug).orElse(null);
            if (query == null) {
                continue;
            }
            try {
                WeatherFetchResult result = client.fetch(query);
                if (result instanceof WeatherFetchResult.Success success) {
                    service.save(slug, success);
                } else if (result instanceof WeatherFetchResult.CredentialRejected rejected) {
                    if (!credentialAlerted) {
                        credentialAlerted = true;
                        log.warn("[travel] OpenWeatherMap rejected credentials (status {}): "
                                + "check OPENWEATHER_API_KEY", rejected.status());
                    }
                } else {
                    warnTransientOnce(slug);
                }
            } catch (RuntimeException e) {
                // 逐城兜底：一城的意外不得影响其余城市，更不得抛上调度线程
                log.warn("[travel] weather refresh for {} failed (keep last known values): {}",
                        slug, e.getClass().getSimpleName());
            }
        }
    }

    private void warnTransientOnce(String slug) {
        long now = System.currentTimeMillis();
        if (now - lastTransientWarnMs >= WARN_INTERVAL_MS) {
            lastTransientWarnMs = now;
            log.warn("[travel] weather transient failure at {} (keep last known values, "
                    + "retry next tick)", slug);
        }
    }
}
