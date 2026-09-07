package com.mooc.backend.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mooc.backend.config.TravelProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Frankfurter 汇率客户端（change: add-travel-services，task 3.2）。
 *
 * <p><b>全仓首个出网客户端</b>——`RestClient`（Spring Framework 6.2 自带，`starter-web` 已含），
 * 不引入 webflux。选 Frankfurter 的理由（design §3.1）：无 key、无日月配额、无强制署名、可自托管。
 *
 * <p><b>超时不在本类设置</b>，由 {@code TravelConfig#travelRestClient} 装配好后注入。原因见那个
 * bean 的注释：在这里调 {@code requestFactory(...)} 会顶掉测试装的 mock factory，让单元测试
 * 静默出网。本类只接一个装配完成的 {@code RestClient}。
 *
 * <p><b>不传 {@code symbols=}</b>：整表约 1KB / 30 币种，筛选省不下什么，而整表才能支撑面板的
 * 「Show all」与纯客户端的币种切换（零额外上游调用）。
 *
 * <p><b>失败一律返回 {@code Optional.empty()}，不抛异常。</b> 调用方是刷新任务，它对 429 /
 * 超时 / 5xx / 畸形 JSON 的处置完全相同——保留旧值、跳过本轮、等下个 tick。区分这四种只会让
 * 调用方写四个一模一样的 catch。（天气那侧不同：401/403 不可自愈，须单独告警，见 task 4.x。
 * 汇率无 key，本就不存在凭据失败。）
 */
@Component
public class ExchangeRateClient {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateClient.class);

    private static final String BASE_URL = "https://api.frankfurter.dev/v1";

    private final RestClient restClient;
    private final String base;

    ExchangeRateClient(RestClient travelRestClient, TravelProperties props) {
        this.restClient = travelRestClient;
        this.base = props.exchangeRate().base();
    }

    /**
     * 拉一次最新牌价。
     *
     * @return 上游不可用 / 响应不可用时为空——调用方据此保留旧值
     */
    public Optional<ExchangeRateSnapshotData> fetchLatest() {
        String url = BASE_URL + "/latest?base=" + base;
        try {
            LatestResponse body = restClient.get().uri(url).retrieve().body(LatestResponse.class);
            return toSnapshot(body);
        } catch (RestClientException e) {
            // 涵盖 4xx/5xx（含 429）、传输异常（连接被拒 / read timeout）与反序列化失败。
            // Frankfurter 无 key，URL 里没有凭据，可以安全记 URL。
            log.warn("[travel] exchange-rate fetch failed (keep last known values): {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 结构合法但内容无用的响应也当失败：写一张空表进缓存，会让前端拿到 {@code rates: {}}、
     * 渲染出一个空列表，而不是走降级空态。
     */
    private Optional<ExchangeRateSnapshotData> toSnapshot(LatestResponse body) {
        if (body == null || body.date() == null || body.rates() == null || body.rates().isEmpty()) {
            log.warn("[travel] exchange-rate response missing date or rates (keep last known values)");
            return Optional.empty();
        }
        String upstreamBase = body.base() == null ? base : body.base();
        return Optional.of(new ExchangeRateSnapshotData(upstreamBase, body.date(), Map.copyOf(body.rates())));
    }

    /**
     * 上游响应形状。{@code amount} 我们不用（固定 1.0），忽略未知字段以容忍上游加字段。
     * {@code date} 由 Jackson 直接绑成 {@code LocalDate}（ISO-8601）——上游给的是日期而非时刻，
     * 不该在这一层被本地化成 {@code Instant}。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LatestResponse(String base, LocalDate date, Map<String, Double> rates) {
    }
}
