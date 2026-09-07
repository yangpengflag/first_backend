package com.mooc.backend.service;
import com.mooc.backend.service.WeatherCityQueries;

import com.mooc.backend.service.CitySlugs;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 城市 slug → 上游查询串映射单元测试（change: add-travel-services，task 2.1）。
 *
 * <p>为什么是常量映射而不是数据库列（design §2 / §12）：`PlacesSeeder` 是 upsert-by-slug 的
 * 插入-only 播种器，而 `ddl-auto: update` 只加列不回填——加 `City.centerLat/centerLng`
 * 会让已有 11 行永久为 NULL。代码常量天生「已回填」。
 *
 * <p>映射的**值是完整的上游查询参数串**，不是城市名：个别城市若哪天解析不对，可就地换成
 * `lat=..&lon=..` 形式而不动调用方（design §11.4 的口子）。11 城已于 2026-09-05 用真实 key
 * 逐个核对，全部解析正确，故当前一律是 `q={CityName},CN` 形态。
 */
class WeatherCityQueriesTest {

    /** 11 个策展城市全部命中。列表与 `PlacesSeeder` 的 `CitySeed` 一一对应。 */
    @Test
    void allElevenCuratedSlugsResolve() {
        assertThat(WeatherCityQueries.slugs()).containsExactlyInAnyOrder(
                "beijing", "shanghai", "xi-an", "chengdu", "hangzhou", "guilin",
                "lhasa", "lijiang", "guangzhou", "chongqing", "fuzhou");

        for (String slug : WeatherCityQueries.slugs()) {
            assertThat(WeatherCityQueries.queryFor(slug)).as("query for %s", slug).isPresent();
        }
    }

    /**
     * slug 的形态必须与 {@code CitySlugs.slugify(name)} 的产出一致，否则映射永远命中不了
     * 真实城市。{@code Xi'an → xi-an} 是唯一一个不平凡的例子（撇号折叠成 `-`）。
     */
    @Test
    void slugsMatchWhatTheSeederGenerates() {
        assertThat(WeatherCityQueries.queryFor("xi-an")).contains("q=Xi'an,CN");
        assertThat(WeatherCityQueries.queryFor("xian")).isEmpty();  // 未经 slugify 的形态不该混进来
        assertThat(WeatherCityQueries.queryFor("xi'an")).isEmpty();

        // 逐条反向核对：把查询串里的城市名喂给播种器用的同一个 slugify，必须还原出这个 key。
        // 这条守的是「新增城市时手写 slug 写错」——错了在这里红，而不是等到线上天气一直 404。
        // 只对 `q=` 形态的值反查：若某城将来换成 `lat=..&lon=..`（design §11.4 的口子），
        // 那个值里根本没有城市名可以 slugify，跳过是正确行为而非漏检。
        for (String slug : WeatherCityQueries.slugs()) {
            String query = WeatherCityQueries.queryFor(slug).orElseThrow();
            if (!query.startsWith("q=")) {
                continue;
            }
            String cityName = query.substring("q=".length()).replace(",CN", "");
            assertThat(CitySlugs.slugify(cityName)).as("slugify(%s)", cityName).isEqualTo(slug);
        }
    }

    /**
     * 值是**上游查询参数串**（`q={CityName},CN`），不是裸城市名——调用方直接把它拼进 query，
     * 个别城市哪天解析不对就能就地换成 `lat=..&lon=..` 而不动任何调用方（design §11.4）。
     *
     * <p>`,CN` 不可省——否则 `Fuzhou` 等名字有跨国歧义，会拿到别国的同名城市。
     */
    @Test
    void valuesAreUpstreamQueryStringsScopedToChina() {
        assertThat(WeatherCityQueries.queryFor("hangzhou")).contains("q=Hangzhou,CN");
        assertThat(WeatherCityQueries.queryFor("fuzhou")).contains("q=Fuzhou,CN");

        for (String slug : WeatherCityQueries.slugs()) {
            assertThat(WeatherCityQueries.queryFor(slug).orElseThrow())
                    .as("query for %s must be a query-parameter string scoped to CN", slug)
                    .startsWith("q=")
                    .endsWith(",CN");
        }
    }

    /**
     * 未收录的 slug 返回空而非抛异常。这条决定了上层的形态：天气刷新遍历映射本身
     * （不会遇到未知 slug），而读端点收到任意 slug 时靠这个空值翻成 404 `CITY_NOT_FOUND`。
     * 抛异常会让「用户输错 slug」和「程序 bug」在同一个通道里，分不开。
     */
    @Test
    void unknownSlugReturnsEmptyInsteadOfThrowing() {
        assertThat(WeatherCityQueries.queryFor("atlantis")).isEmpty();
        assertThat(WeatherCityQueries.queryFor("")).isEmpty();
        assertThat(WeatherCityQueries.queryFor(null)).isEmpty();
    }

    /** 映射对外只读：调用方拿到的集合不能被改坏（它是全进程共享的一份常量）。 */
    @Test
    void mappingIsImmutable() {
        Optional<String> before = WeatherCityQueries.queryFor("beijing");

        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> WeatherCityQueries.slugs().add("atlantis"));

        assertThat(WeatherCityQueries.queryFor("beijing")).isEqualTo(before);
    }
}
