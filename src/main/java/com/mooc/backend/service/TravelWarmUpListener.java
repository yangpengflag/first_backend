package com.mooc.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 冷启动预热（change: add-travel-services，task 1.4）。
 *
 * <p><b>为什么需要</b>：{@code @Scheduled(cron = ...)} <b>不支持 {@code initialDelay}</b>——两者
 * 组合会在启动时抛 {@code IllegalStateException}。所以「启动后立刻刷一次」只能另找入口，用
 * {@link ApplicationReadyEvent} 与 cron 并存。汇率另有快照表回填，天气无持久化、否则要空窗到下个 tick。
 *
 * <p><b>必须异步投递，这是本类存在的全部理由。</b> {@code @EventListener} 默认在<b>发布事件的那个
 * 线程</b>上同步执行，也就是 {@code main}。天气预热最坏是 22 次上游调用 × 5s read timeout ≈ 110s，
 * 同步跑就是把启动阻塞近两分钟：容器健康检查会先超时判定失败，编排层直接重启，重启后再卡两分钟，
 * 形成重启循环。故本方法只做一件事——把任务丢给调度线程池，自己立刻返回。
 *
 * <p>用 {@link TaskScheduler} 而不是 {@code @Async}：投递目标就是 cron 用的同一个池
 * （{@code spring.task.scheduling.pool.size: 2}），不必为此再配一套 executor。池大小 2 也是为此——
 * 默认值是 1，预热的天气任务会把 cron 触发的汇率任务挡在 110s 之后。
 */
@Component
public class TravelWarmUpListener {

    private static final Logger log = LoggerFactory.getLogger(TravelWarmUpListener.class);

    private final TaskScheduler taskScheduler;
    private final List<TravelRefreshJob> jobs;

    TravelWarmUpListener(TaskScheduler taskScheduler, List<TravelRefreshJob> jobs) {
        this.taskScheduler = taskScheduler;
        this.jobs = jobs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpAsync() {
        for (TravelRefreshJob job : jobs) {
            log.info("[travel] scheduling warm-up refresh for {}", job.jobName());
            // schedule(now) 而非直接 run()：立刻返回，任务在调度线程上跑。
            taskScheduler.schedule(() -> runQuietly(job), Instant.now());
        }
    }

    /**
     * 预热失败不得影响启动，也不得让调度线程带着异常退出——实现方本应自己吞掉上游异常
     * （见 {@link TravelRefreshJob#refresh()}），这里是第二道保险。
     */
    private void runQuietly(TravelRefreshJob job) {
        try {
            job.refresh();
        } catch (RuntimeException e) {
            log.warn("[travel] warm-up refresh for {} failed (cron will retry): {}", job.jobName(), e.getMessage());
        }
    }
}
