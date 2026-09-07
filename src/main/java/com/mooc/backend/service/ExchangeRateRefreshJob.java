package com.mooc.backend.service;

import com.mooc.backend.service.ExchangeRateClient;
import com.mooc.backend.service.ExchangeRateSnapshotData;
import com.mooc.backend.config.TravelProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 汇率刷新任务（change: add-travel-services，task 3.5）。
 *
 * <p><b>本类是汇率数据唯一的出网路径。</b> 请求路径（controller / `@Tool`）只读 Redis 与快照表
 * （design §1.1）——把出网放在调度线程上，用户请求的延迟与上游可用性彻底解耦。
 *
 * <p>每轮四步，任一步失败就干净地放弃本轮：
 * <ol>
 *   <li>{@code enabled} 检查——停用即彻底静默，连锁都不抢（抢了会挡住同集群里启用的实例）</li>
 *   <li>抢锁——抢不到说明别的实例在跑，跳过。<b>方向与缓存 fail-safe 相反</b>：缓存读失败可以
 *       退化成查库仍然正确，锁抢不到必须放弃，「抢不到就当抢到了」会让这把锁失去全部意义</li>
 *   <li>出网——失败返回空，保留旧值等下个 tick（不做退避重试，见 task 0.2）</li>
 *   <li>落盘——先快照后 Redis，由 {@link ExchangeRateService#save} 保证顺序</li>
 * </ol>
 *
 * <p><b>跑完不释放锁</b>（design §4）：锁 TTL 的第二个作用是「两轮之间的最小间隔」。删锁会让
 * 多实例间几秒的时钟偏移足以让第二个实例在第一个刚删锁时抢到并重跑一轮。
 *
 * <p><b>异常一律在本类边界内 catch。</b> 调度线程上抛出的异常最好的情况是被框架记一条日志，
 * 最坏的情况是让后续 tick 静默停摆；两种都不该发生在一个「拉取失败就等下轮」的任务里。
 */
@Component
public class ExchangeRateRefreshJob implements TravelRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateRefreshJob.class);

    /** 锁名（design §4 的 {@code travel:refresh-lock:{job}}，job ∈ {rates, weather}）。 */
    private static final String JOB_NAME = "rates";

    private final ExchangeRateClient client;
    private final ExchangeRateService service;
    private final RefreshLock lock;
    private final boolean enabled;

    ExchangeRateRefreshJob(ExchangeRateClient client, ExchangeRateService service,
                           RefreshLock lock, TravelProperties props) {
        this.client = client;
        this.service = service;
        this.lock = lock;
        this.enabled = props.exchangeRate().enabled();
    }

    @Override
    public String jobName() {
        return JOB_NAME;
    }

    /**
     * cron 入口。表达式走占位符而非写死字面量——写死会让 yml 里的 {@code refresh-cron} 变成装饰。
     *
     * <p><b>不能加 {@code initialDelay}</b>：{@code @Scheduled} 的 cron 与 initialDelay 组合会抛
     * {@code IllegalStateException}。冷启动首刷走 {@link TravelWarmUpListener}。
     */
    @Scheduled(cron = "${app.travel.exchange-rate.refresh-cron}")
    void scheduledRefresh() {
        refresh();
    }

    @Override
    public void refresh() {
        if (!enabled) {
            return;
        }
        if (!lock.tryAcquire(JOB_NAME)) {
            log.debug("[travel] rates refresh skipped (another instance holds the lock)");
            return;
        }
        try {
            Optional<ExchangeRateSnapshotData> fetched = client.fetchLatest();
            if (fetched.isEmpty()) {
                // 客户端已记过 warn；这里不重复告警，也不写任何东西——旧值原样保留
                return;
            }
            service.save(fetched.get());
            log.info("[travel] rates refreshed (upstream date {})", fetched.get().upstreamDate());
        } catch (RuntimeException e) {
            // 出网、序列化、落盘的任何意外都在此止步：调度线程不该因为一次刷新失败而受影响
            log.warn("[travel] rates refresh failed (keep last known values): {}", e.getMessage());
        }
    }
}
