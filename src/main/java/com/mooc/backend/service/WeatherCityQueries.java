package com.mooc.backend.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 城市 slug → OpenWeatherMap 查询串映射（change: add-travel-services，task 2.2）。
 *
 * <p><b>为什么不进数据库</b>（design §2）：{@code PlacesSeeder} 是 upsert-by-slug 的插入-only
 * 播种器，而 {@code ddl-auto: update} 只加列不回填——给 {@code City} 加坐标列会让已有 11 行
 * 永久为 NULL，还要改 {@code City.create} 签名、动 {@code places} capability 的 spec。
 * 代码常量天生「已回填」，且城市是编辑策展的固定列表、城市名是稳定的公开事实。
 *
 * <p><b>值是完整的上游查询参数串，不只是城市名。</b> 这样个别城市若哪天解析不对，可就地换成
 * {@code "lat=30.25&lon=120.15"} 形式而不动任何调用方。11 城已于 2026-09-05 用真实 key 逐个
 * 核对（`Xi'an` 的撇号、`Fuzhou` 的闽赣同名、`Chengdu`/`Chongqing` 三处风险均实测排除），
 * 故当前一律是 {@code {CityName},CN} 形态。
 *
 * <p><b>{@code ,CN} 不可省</b>：`Fuzhou` 一类名字有跨国歧义，不限定国家就可能拿到别国同名城市。
 *
 * <p><b>新增城市时须同时改两处</b>：{@code PlacesSeeder} 的 {@code CitySeed} 列表与本类
 * （查询串 + 双语名，同一个文件）。两处都在代码里、同一次评审能看见——这是相对「数据库加列」
 * 被选中的原因，不是被忽略的缺点。key 的形态必须是 {@code CitySlugs.slugify(name)} 的产出
 * （{@code Xi'an → xi-an}），否则永远命中不了。
 *
 * <p><b>双语名（{@link Names}）也在本类</b>：天气响应的 {@code city_name} / {@code city_name_zh}
 * 必须出网（面板只有 slug，没有城市实体），而中文名无从推导——与其让前端再存一份 slug→名字
 * 映射（第二份真相），不如与查询串同源维护。
 */
public final class WeatherCityQueries {

    private static final Map<String, String> QUERIES = queries();
    private static final Map<String, Names> NAMES = names();

    /** 双语城市名：{@code en} 主显（英文界面），{@code zh} 副显（与 places 的双语约定一致）。 */
    public record Names(String en, String zh) {
    }

    private WeatherCityQueries() {
        // utility
    }

    /**
     * 某城市的双语名。与 {@link #queryFor} 同语义：未收录为空、不抛异常。
     */
    public static Optional<Names> namesFor(String citySlug) {
        return citySlug == null ? Optional.empty() : Optional.ofNullable(NAMES.get(citySlug));
    }

    /**
     * 某个城市的上游查询串。
     *
     * @param citySlug 城市 slug，允许为 {@code null}（读端点的入参来自 HTTP，不可信）
     * @return 未收录则为空。<b>不抛异常</b>：上层靠这个空值翻成 404 {@code CITY_NOT_FOUND}，
     *         抛异常会把「用户给了未知 slug」和「程序 bug」混进同一个通道。
     */
    public static Optional<String> queryFor(String citySlug) {
        return citySlug == null ? Optional.empty() : Optional.ofNullable(QUERIES.get(citySlug));
    }

    /** 全部策展城市的 slug，供批量刷新任务遍历。插入序（与播种器一致），只读。 */
    public static Set<String> slugs() {
        return QUERIES.keySet();
    }

    /** 与 {@code PlacesSeeder} 的 11 个 {@code CitySeed} 一一对应，顺序也保持一致。 */
    private static Map<String, String> queries() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("beijing", "q=Beijing,CN");
        m.put("shanghai", "q=Shanghai,CN");
        m.put("xi-an", "q=Xi'an,CN");
        m.put("chengdu", "q=Chengdu,CN");
        m.put("hangzhou", "q=Hangzhou,CN");
        m.put("guilin", "q=Guilin,CN");
        m.put("lhasa", "q=Lhasa,CN");
        m.put("lijiang", "q=Lijiang,CN");
        m.put("guangzhou", "q=Guangzhou,CN");
        m.put("chongqing", "q=Chongqing,CN");
        m.put("fuzhou", "q=Fuzhou,CN");
        return java.util.Collections.unmodifiableMap(m);
    }

    /** key 集与 {@link #queries()} 完全一致（同一份策展清单的两个视图）。 */
    private static Map<String, Names> names() {
        Map<String, Names> m = new LinkedHashMap<>();
        m.put("beijing", new Names("Beijing", "北京"));
        m.put("shanghai", new Names("Shanghai", "上海"));
        m.put("xi-an", new Names("Xi'an", "西安"));
        m.put("chengdu", new Names("Chengdu", "成都"));
        m.put("hangzhou", new Names("Hangzhou", "杭州"));
        m.put("guilin", new Names("Guilin", "桂林"));
        m.put("lhasa", new Names("Lhasa", "拉萨"));
        m.put("lijiang", new Names("Lijiang", "丽江"));
        m.put("guangzhou", new Names("Guangzhou", "广州"));
        m.put("chongqing", new Names("Chongqing", "重庆"));
        m.put("fuzhou", new Names("Fuzhou", "福州"));
        return java.util.Collections.unmodifiableMap(m);
    }
}
