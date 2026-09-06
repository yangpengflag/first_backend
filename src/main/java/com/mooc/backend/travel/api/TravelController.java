package com.mooc.backend.travel.api;

import com.mooc.backend.auth.exception.ErrorCode;
import com.mooc.backend.travel.config.TravelProperties;
import com.mooc.backend.travel.domain.WeatherCityQueries;
import com.mooc.backend.travel.exception.TravelException;
import com.mooc.backend.travel.service.ExchangeRateService;
import com.mooc.backend.travel.service.WeatherService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 旅行实用工具 HTTP 接口（只读，公开免鉴权；change: add-travel-services，task 5.2）。
 *
 * <p><b>请求路径不出网</b>：两个读端点只碰 Redis（延迟上界 = 500ms），降级返回 200 + null
 * 字段而非 5xx——旅行信息是增强，不该让页面失败。出网只发生在刷新任务里（design §1.1）。
 *
 * <p>降级语义对照（design §2）：汇率/天气「无数据」由 Service 返 {@code null} 表达；
 * 「slug 未收录」才是 404 {@code CITY_NOT_FOUND}——它指示调用方给错了参数，不是上游故障。
 */
@RestController
@RequestMapping(value = "/api/travel", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "旅行实用工具", description = "参考汇率与目的地城市天气（展示参考，非结算依据）。")
public class TravelController {

    private final ExchangeRateService exchangeRateService;
    private final WeatherService weatherService;
    private final String baseCurrency;

    public TravelController(ExchangeRateService exchangeRateService, WeatherService weatherService,
                            TravelProperties props) {
        this.exchangeRateService = exchangeRateService;
        this.weatherService = weatherService;
        this.baseCurrency = props.exchangeRate().base();
    }

    @Operation(summary = "参考汇率", description = "CNY 基准的参考汇率表（ECB 派生交叉汇率，仅展示参考）。")
    @GetMapping("/rates")
    public ResponseEntity<TravelRatesResponse> rates() {
        return ResponseEntity.ok(TravelRatesResponse.from(exchangeRateService.getRates(), baseCurrency));
    }

    @Operation(summary = "城市天气", description = "策展城市的当前天气与 3h 粒度预报（原始桶，前端按当地日聚合）。")
    @GetMapping("/weather")
    public ResponseEntity<TravelWeatherResponse> weather(@RequestParam("city") String city) {
        // 404 唯一触发条件：slug 不在策展映射内（映射里有 slug 就一定有查询串，design §6）
        if (WeatherCityQueries.queryFor(city).isEmpty()) {
            throw new TravelException(ErrorCode.CITY_NOT_FOUND);
        }
        return ResponseEntity.ok(TravelWeatherResponse.from(city, weatherService.getWeather(city)));
    }
}
