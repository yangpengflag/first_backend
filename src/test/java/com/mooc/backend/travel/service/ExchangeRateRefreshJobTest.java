package com.mooc.backend.travel.service;

import com.mooc.backend.travel.client.ExchangeRateClient;
import com.mooc.backend.travel.client.ExchangeRateSnapshotData;
import com.mooc.backend.travel.config.TravelProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 汇率刷新任务测试（change: add-travel-services，task 3.5）。
 *
 * <p>这个类是唯一会出网的路径（design §1.1：请求路径不出网，出网只在调度线程）。故它的
 * 测试重点全在<b>边界行为</b>而非数据转换——转换在 {@code ExchangeRateClientTest} 与
 * {@code ExchangeRateServiceTest} 已各自覆盖：
 *
 * <ul>
 *   <li>{@code enabled=false} → <b>不出网、不写 Redis、不碰 DB</b>，连锁都不抢
 *       （抢了锁就会挡住同集群里启用的实例，虽然当前不会同时存在，但那是配置巧合不是设计）</li>
 *   <li>抢不到锁 → 跳过本轮，<b>不出网</b>。方向与缓存 fail-safe 相反：缓存读失败可以退化成查库，
 *       锁抢不到必须放弃——「抢不到就当抢到了」会让这把锁彻底失去意义</li>
 *   <li>拉取失败（{@code Optional.empty()}）→ <b>不写任何东西</b>，保留旧值等下个 tick</li>
 *   <li>拉取成功 → 交给 Service 落盘（写顺序由 {@code ExchangeRateServiceTest} 断言）</li>
 *   <li>任何异常<b>不得穿透</b>：调度线程上抛出的异常会让后续 tick 静默停摆</li>
 * </ul>
 */
class ExchangeRateRefreshJobTest {

    private ExchangeRateClient client;
    private ExchangeRateService service;
    private RefreshLock lock;

    @BeforeEach
    void setUp() {
        client = mock(ExchangeRateClient.class);
        service = mock(ExchangeRateService.class);
        lock = mock(RefreshLock.class);
    }

    /** 停用时彻底静默：不抢锁、不出网、不落盘。 */
    @Test
    void disabledDoesNotFetchOrWriteOrEvenTakeTheLock() {
        job(false).refresh();

        verifyNoInteractions(lock);
        verifyNoInteractions(client);
        verifyNoInteractions(service);
    }

    /**
     * 抢不到锁：另一个实例正在跑本轮，直接返回。**关键是不出网**——多实例各自出网正是
     * 这把锁要防的事（上游调用翻倍 + 并发写同一 key）。
     */
    @Test
    void skipsRoundWithoutFetchingWhenLockNotAcquired() {
        when(lock.tryAcquire("rates")).thenReturn(false);

        job(true).refresh();

        verifyNoInteractions(client);
        verifyNoInteractions(service);
    }

    /** 拉取失败：**什么都不写**。写一份空值进去等于把上游的一次抖动变成我们的数据丢失。 */
    @Test
    void upstreamFailureWritesNothing() {
        when(lock.tryAcquire("rates")).thenReturn(true);
        when(client.fetchLatest()).thenReturn(Optional.empty());

        job(true).refresh();

        verify(service, never()).save(any());
    }

    /** 成功路径：拉到的数据原样交给 Service（落盘顺序是 Service 的职责，那里已有 InOrder 断言）。 */
    @Test
    void successfulFetchIsHandedToTheService() {
        ExchangeRateSnapshotData data = new ExchangeRateSnapshotData(
                "CNY", LocalDate.of(2026, 9, 4), Map.of("USD", 0.14901));
        when(lock.tryAcquire("rates")).thenReturn(true);
        when(client.fetchLatest()).thenReturn(Optional.of(data));

        job(true).refresh();

        verify(service).save(data);
    }

    /**
     * 落盘抛异常不得穿透到调度线程。Spring 的 {@code TaskScheduler} 对 {@code @Scheduled} 的
     * 固定周期任务会在异常后继续下个 tick，但预热路径与将来的包装未必同样宽容——
     * 在任务边界内 catch 是唯一不依赖调用方善意的做法。
     */
    @Test
    void doesNotLetPersistenceFailureEscape() {
        when(lock.tryAcquire("rates")).thenReturn(true);
        when(client.fetchLatest()).thenReturn(Optional.of(new ExchangeRateSnapshotData(
                "CNY", LocalDate.of(2026, 9, 4), Map.of("USD", 0.14901))));
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(service).save(any());

        assertThatCode(() -> job(true).refresh()).doesNotThrowAnyException();
    }

    /** 客户端抛异常（它本该自己 catch，但不能依赖）同样不穿透。 */
    @Test
    void doesNotLetClientFailureEscape() {
        when(lock.tryAcquire("rates")).thenReturn(true);
        when(client.fetchLatest()).thenThrow(new RuntimeException("boom"));

        assertThatCode(() -> job(true).refresh()).doesNotThrowAnyException();
    }

    /** 锁名是 {@code rates}（design §4 的 `travel:refresh-lock:{job}`，job ∈ {rates, weather}）。 */
    @Test
    void usesRatesAsJobName() {
        assertThat(job(true).jobName()).isEqualTo("rates");
    }

    /**
     * <b>刷新任务不得主动释放锁</b>（design §4）：TTL 的第二个作用是「两轮之间的最小间隔」，
     * 删锁会让多实例间几秒的时钟偏移足以让第二个实例在第一个刚删锁时抢到并重跑一轮。
     */
    @Test
    void neverReleasesTheLockAfterTheRound() {
        when(lock.tryAcquire("rates")).thenReturn(true);
        when(client.fetchLatest()).thenReturn(Optional.empty());

        job(true).refresh();

        // RefreshLock 本身就没有 release/unlock 方法（RefreshLockTest 已断言），
        // 这里补断言任务没有绕过它去直接删 key。
        verify(lock).tryAcquire("rates");
        org.mockito.Mockito.verifyNoMoreInteractions(lock);
    }

    /**
     * cron 表达式从配置读，不硬编码在注解里。{@code @Scheduled(cron = "${...}")} 才能让
     * design §4 的 `refresh-cron` 真正生效——写死注解值会让 yml 里那行变成装饰。
     */
    @Test
    void scheduledCronComesFromConfiguration() throws NoSuchMethodException {
        Method scheduled = ExchangeRateRefreshJob.class.getDeclaredMethod("scheduledRefresh");
        Scheduled annotation = scheduled.getAnnotation(Scheduled.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.cron()).isEqualTo("${app.travel.exchange-rate.refresh-cron}");
        assertThat(annotation.initialDelayString())
                .as("@Scheduled(cron) rejects initialDelay; warm-up goes through ApplicationReadyEvent")
                .isEmpty();
    }

    /** 停用时连锁都不抢，故也不会有任何 Redis 交互（`RefreshLock` 是唯一的 Redis 触点）。 */
    @Test
    void disabledDoesNotAcquireAnyLockKey() {
        job(false).refresh();

        verify(lock, never()).tryAcquire(anyString());
    }

    private ExchangeRateRefreshJob job(boolean enabled) {
        return new ExchangeRateRefreshJob(client, service, lock, props(enabled));
    }

    private static TravelProperties props(boolean enabled) {
        return new TravelProperties(
                new TravelProperties.ExchangeRate(enabled, "CNY", "0 30 3 * * *",
                        Duration.ofHours(26), Duration.ofDays(7)),
                null,
                new TravelProperties.Client(Duration.ofSeconds(2), Duration.ofSeconds(5)),
                Duration.ofMinutes(5));
    }
}
