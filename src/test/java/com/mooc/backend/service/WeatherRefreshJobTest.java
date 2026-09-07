package com.mooc.backend.service;
import com.mooc.backend.service.RefreshLock;
import com.mooc.backend.service.WeatherRefreshJob;
import com.mooc.backend.service.WeatherService;

import com.mooc.backend.service.CurrentWeather;
import com.mooc.backend.service.ForecastBucket;
import com.mooc.backend.service.WeatherClient;
import com.mooc.backend.service.WeatherFetchResult;
import com.mooc.backend.config.TravelProperties;
import com.mooc.backend.service.WeatherCityQueries;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 天气批量刷新任务单元测试（change: add-travel-services，task 4.3 / 4.4）。
 *
 * <p>照 {@code ExchangeRateRefreshJobTest} 的套路（mock {@code RefreshLock}），外加天气特有的
 * 批量语义：
 *
 * <ul>
 *   <li><b>单城失败不中断批量</b>——某城 429 不该让其余 10 城也空窗（spec「单城失败 SHALL NOT
 *       中断其余城市的刷新」）；</li>
 *   <li><b>凭据被拒（401/403）输出指名 {@code OPENWEATHER_API_KEY} 的告警</b>，且<b>每轮至多一条</b>
 *       ——11 城共用同一把 key，逐城告警就是 11 条同文刷屏；它也不与瞬时故障共用节流窗口
 *       （两者处置不同：一个要人来改配置，一个等下个 tick）；</li>
 *   <li><b>瞬时告警节流</b>——批量一轮 11 城全 429 也只出一条 warn，照 {@code warnOnce} 10s 窗口。</li>
 * </ul>
 */
class WeatherRefreshJobTest {

    private RefreshLock lock;
    private ObjectProvider<WeatherClient> clientProvider;
    private WeatherClient client;
    private WeatherService service;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        lock = mock(RefreshLock.class);
        when(lock.tryAcquire(anyString())).thenReturn(true);
        clientProvider = mock(ObjectProvider.class);
        client = mock(WeatherClient.class);
        when(clientProvider.getIfAvailable()).thenReturn(client);
        service = mock(WeatherService.class);
    }

    @AfterEach
    void detachLogAppender() {
        if (logAppender != null) {
            ((Logger) LoggerFactory.getLogger(WeatherRefreshJob.class)).detachAppender(logAppender);
            logAppender.stop();
            logAppender = null;
        }
    }

    /** enabled=false 或客户端未装配（key 空）都彻底静默：不抢锁、不出网、不落盘。 */
    @Test
    void disabledOrUnassembledClientSkipsEverything() {
        WeatherRefreshJob disabled = new WeatherRefreshJob(clientProvider, service, lock, props(false));
        disabled.refresh();

        WeatherRefreshJob unassembled = new WeatherRefreshJob(emptyProvider(), service, lock, props(true));
        unassembled.refresh();

        verifyNoInteractions(lock, client, service);
    }

    /** 抢不到锁：别的实例在跑，本轮跳过（方向与缓存 fail-safe 相反——这里必须放弃）。 */
    @Test
    void lockNotAcquiredSkipsRefresh() {
        when(lock.tryAcquire("weather")).thenReturn(false);
        WeatherRefreshJob job = job();

        job.refresh();

        verifyNoInteractions(client, service);
    }

    /** 批量遍历策展映射：11 城各拉一次、各存一个 per-city key，查询串来自 §2 的常量映射。 */
    @Test
    void refreshesAllCuratedCities() {
        WeatherFetchResult.Success success = success();
        when(client.fetch(anyString())).thenReturn(success);
        WeatherRefreshJob job = job();

        job.refresh();

        ArgumentCaptor<String> queries = ArgumentCaptor.forClass(String.class);
        verify(client, times(WeatherCityQueries.slugs().size())).fetch(queries.capture());
        // 每个策展 slug 的查询串恰好被用一次
        assertThat(queries.getAllValues())
                .containsExactlyInAnyOrderElementsOf(
                        WeatherCityQueries.slugs().stream()
                                .map(slug -> WeatherCityQueries.queryFor(slug).orElseThrow())
                                .toList());

        ArgumentCaptor<String> slugs = ArgumentCaptor.forClass(String.class);
        verify(service, times(WeatherCityQueries.slugs().size())).save(slugs.capture(), any());
        assertThat(slugs.getAllValues())
                .containsExactlyInAnyOrderElementsOf(WeatherCityQueries.slugs());
    }

    /** 单城失败不中断批量：第 3 城抛异常，其余 10 城照常落盘。 */
    @Test
    void singleCityFailureDoesNotStopBatch() {
        WeatherFetchResult.Success success = success();
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 3) {
                throw new RuntimeException("boom");
            }
            return success;
        }).when(client).fetch(anyString());
        WeatherRefreshJob job = job();

        job.refresh(); // 不得抛出

        verify(service, times(WeatherCityQueries.slugs().size() - 1)).save(anyString(), any());
    }

    /**
     * 全部 401：每城照常尝试（不因第一城被拒而放弃其余），但<b>告警每轮只出一条</b>且指名
     * {@code OPENWEATHER_API_KEY}——11 城共用一把 key，逐城告警是 11 条刷屏。
     */
    @Test
    void credentialRejectionAlertsOncePerRoundNamingConfigKey() {
        attachLogAppender();
        when(client.fetch(anyString()))
                .thenReturn(new WeatherFetchResult.CredentialRejected(401));
        WeatherRefreshJob job = job();

        job.refresh();

        verify(client, times(WeatherCityQueries.slugs().size())).fetch(anyString());
        verify(service, times(0)).save(anyString(), any());
        long alerts = logAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("OPENWEATHER_API_KEY"))
                .count();
        assertThat(alerts).as("one actionable alert per round, not one per city").isEqualTo(1);
    }

    /**
     * 凭据告警<b>不与瞬时故障共用节流窗口</b>：本轮先来一次 429（占掉瞬时告警的节流窗口），
     * 随后的 401 仍必须发出自己的告警——两者处置不同，一个等下个 tick，一个要人改配置。
     */
    @Test
    void credentialAlertNotSuppressedByTransientThrottleWindow() {
        attachLogAppender();
        when(client.fetch(anyString()))
                .thenReturn(new WeatherFetchResult.TransientFailure())
                .thenReturn(new WeatherFetchResult.CredentialRejected(401));
        WeatherRefreshJob job = job();

        job.refresh();

        assertThat(logAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("OPENWEATHER_API_KEY")).count())
                .isEqualTo(1);
        assertThat(logAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("transient")).count())
                .isEqualTo(1);
    }

    /** 批量全 429：瞬时告警按节流窗口只出一条，不逐城刷屏。 */
    @Test
    void transientWarningsAreThrottledAcrossBatch() {
        attachLogAppender();
        when(client.fetch(anyString())).thenReturn(new WeatherFetchResult.TransientFailure());
        WeatherRefreshJob job = job();

        job.refresh();

        long transientWarns = logAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("transient"))
                .count();
        assertThat(transientWarns).as("throttled to one per window despite 11 cities").isEqualTo(1);
        verify(service, times(0)).save(anyString(), any());
    }

    /** 日志卫生：任何失败分支下日志不得出现 URL、appid 或 key 取值（spec「告警与异常不泄露凭据」）。 */
    @Test
    void logsNeverContainUrlOrApiKey() {
        attachLogAppender();
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                return success();
            }
            if (n == 2) {
                return new WeatherFetchResult.TransientFailure();
            }
            if (n == 3) {
                throw new RuntimeException("I/O error on GET request for "
                        + "\"https://api.openweathermap.org/...\": Read timed out");
            }
            return new WeatherFetchResult.CredentialRejected(401);
        }).when(client).fetch(anyString());
        WeatherRefreshJob job = job();

        job.refresh();

        assertThat(logAppender.list).isNotEmpty();
        assertThat(logAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(message -> {
                    assertThat(message).doesNotContain("api.openweathermap.org");
                    assertThat(message).doesNotContain("appid");
                    assertThat(message).doesNotContain("test-key");
                });
    }

    private WeatherRefreshJob job() {
        return new WeatherRefreshJob(clientProvider, service, lock, props(true));
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<WeatherClient> emptyProvider() {
        ObjectProvider<WeatherClient> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }

    private void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(WeatherRefreshJob.class)).addAppender(logAppender);
    }

    private static WeatherFetchResult.Success success() {
        return new WeatherFetchResult.Success(
                new CurrentWeather(18.4, "overcast clouds", "04d", 72, 3.1),
                List.of(new ForecastBucket(1757046000L, 16.1, 20.2, "Clouds", "overcast clouds", "04d")));
    }

    private static TravelProperties props(boolean enabled) {
        return new TravelProperties(
                new TravelProperties.ExchangeRate(true, "CNY", "0 30 3 * * *",
                        Duration.ofHours(26), Duration.ofDays(7)),
                new TravelProperties.Weather(enabled, "test-key", "0 0 */3 * * *", "en",
                        Duration.ofHours(4), Duration.ofHours(24)),
                new TravelProperties.Client(Duration.ofSeconds(2), Duration.ofSeconds(5)),
                Duration.ofMinutes(5));
    }
}
