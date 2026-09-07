package com.mooc.backend.service;
import com.mooc.backend.service.TravelRefreshJob;
import com.mooc.backend.service.TravelWarmUpListener;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 冷启动预热监听器单元测试（change: add-travel-services，task 1.4）。
 *
 * <p>最要紧的一条是 {@link #doesNotRunJobsOnTheCallingThread()}：监听器默认跑在 {@code main} 上，
 * 一旦有人把 {@code taskScheduler.schedule(...)} 简化成直接 {@code job.refresh()}，启动就会被
 * 天气预热阻塞近两分钟，进而触发容器重启循环。那种退化在集成测试里很难被察觉，这里用
 * 「调用 {@code warmUpAsync()} 后任务尚未执行」把它钉死。
 */
class TravelWarmUpListenerTest {

    /** 预热必须异步：同步执行会把启动阻塞到健康检查超时。 */
    @Test
    void doesNotRunJobsOnTheCallingThread() {
        AtomicBoolean ran = new AtomicBoolean(false);
        TravelRefreshJob job = job("weather", () -> ran.set(true));
        // schedule() 只记录、不执行——真跑了就说明是监听器自己在调用线程上跑的。
        TaskScheduler scheduler = mock(TaskScheduler.class);
        TravelWarmUpListener listener = new TravelWarmUpListener(scheduler, List.of(job));

        listener.warmUpAsync();

        assertThat(ran).isFalse();
        verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
    }

    /** 每个注册的 job 各投递一次；新增第三类数据不需要改监听器。 */
    @Test
    void schedulesEveryRegisteredJob() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        TravelWarmUpListener listener = new TravelWarmUpListener(scheduler,
                List.of(job("rates", () -> {
                }), job("weather", () -> {
                })));

        listener.warmUpAsync();

        verify(scheduler, org.mockito.Mockito.times(2)).schedule(any(Runnable.class), any(Instant.class));
    }

    /** 投递时刻是「现在」，不是某个未来时间——预热的意义就在于不等第一个 cron tick。 */
    @Test
    void schedulesImmediately() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        TravelWarmUpListener listener = new TravelWarmUpListener(scheduler, List.of(job("rates", () -> {
        })));
        Instant before = Instant.now();

        listener.warmUpAsync();

        org.mockito.ArgumentCaptor<Instant> at = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(scheduler).schedule(any(Runnable.class), at.capture());
        assertThat(at.getValue()).isBetween(before, Instant.now());
    }

    /**
     * 一个 job 抛异常不得影响其余 job，也不得让调度线程带着异常退出。
     * 用「立刻执行提交的 Runnable」的 scheduler 来触发这条路径。
     */
    @Test
    void oneFailingJobDoesNotAffectTheOthers() {
        AtomicBoolean secondRan = new AtomicBoolean(false);
        TaskScheduler scheduler = runImmediately();
        TravelWarmUpListener listener = new TravelWarmUpListener(scheduler, List.of(
                job("rates", () -> {
                    throw new IllegalStateException("upstream down");
                }),
                job("weather", () -> secondRan.set(true))));

        listener.warmUpAsync(); // 不抛

        assertThat(secondRan).isTrue();
    }

    /** 没有注册任何 job（两个开关都关）时不该投递任何东西。 */
    @Test
    void noJobsMeansNoScheduling() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        new TravelWarmUpListener(scheduler, List.of()).warmUpAsync();

        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    /** 监听的是 {@link ApplicationReadyEvent}——不是 {@code ContextRefreshedEvent}（那时 web 层还没就绪）。 */
    @Test
    void listensToApplicationReadyEvent() throws Exception {
        var listener = TravelWarmUpListener.class.getMethod("warmUpAsync")
                .getAnnotation(org.springframework.context.event.EventListener.class);

        assertThat(listener).isNotNull();
        assertThat(listener.value()).containsExactly(ApplicationReadyEvent.class);
    }

    private static TravelRefreshJob job(String name, Runnable body) {
        return new TravelRefreshJob() {
            @Override
            public String jobName() {
                return name;
            }

            @Override
            public void refresh() {
                body.run();
            }
        };
    }

    /** 就地执行提交的任务，用来走通「job 抛异常」这条分支。 */
    private static TaskScheduler runImmediately() {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        org.mockito.Mockito.when(scheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenAnswer(inv -> {
                    inv.<Runnable>getArgument(0).run();
                    return null;
                });
        return scheduler;
    }
}
