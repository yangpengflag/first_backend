package com.mooc.backend.travel.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 给 travel 响应里的五个可空字段补上 OpenAPI 3.1 的可空表达（change: add-travel-services，task 7.1）。
 *
 * <p><b>为什么需要这个 bean</b>：springdoc 2.8.8 在 OpenAPI 3.1 模式下会<b>直接丢弃</b>
 * {@code @Schema(nullable = true)}（既不输出 {@code nullable: true} 也不转成 {@code type: ["x","null"]}）。
 * 而前端 {@code strict: true} 下 {@code T | undefined} 接不住降级返回的 {@code null}（design §6），
 * 必须让生成的 TS 含 {@code | null}。3.1 里可空的标准写法是
 * {@code oneOf: [原 schema, {type:"null"}]}，本 customizer 对两个响应 DTO 的那五个字段做这个包裹，
 * 既不改动全局 OpenAPI 版本，也不影响其它端点。
 *
 * <p>包裹后字段描述保留在 wrapper 上，原 schema 作为 oneOf 第一支。openapi-typescript 7 会把
 * {@code {type:"null"}} 分支翻成 {@code null}，于是生成 {@code string | null} /
 * {@code CurrentWeather | null} 等。
 */
@Component
public class TravelOpenApiCustomizer implements OpenApiCustomizer {

    private static final List<String> RATES_NULLABLE = List.of("as_of", "fetched_at", "rates");
    private static final List<String> WEATHER_NULLABLE = List.of("fetched_at", "current", "forecast");

    @Override
    @SuppressWarnings("unchecked")
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        Map<String, Schema> schemas = openApi.getComponents().getSchemas();
        RATES_NULLABLE.forEach(p -> wrapNullable(schemas.get("TravelRatesResponse"), p));
        WEATHER_NULLABLE.forEach(p -> wrapNullable(schemas.get("TravelWeatherResponse"), p));
    }

    private void wrapNullable(Schema<?> schema, String property) {
        if (schema == null || schema.getProperties() == null) {
            return;
        }
        Map<String, Schema> props = (Map<String, Schema>) schema.getProperties();
        Schema<?> original = props.get(property);
        if (original == null) {
            return;
        }
        // 关键：springdoc 2.8.8 在 3.1 模式下会把 Schema.setType("null") 静默丢弃（序列化成 {}），
        // 必须改用 types 数组——它会序列化成 type:["null"]，openapi-typescript 7 同样识别为 null。
        Schema<?> nullBranch = new Schema<>();
        nullBranch.setTypes(Set.of("null"));

        List<Schema> oneOf = new ArrayList<>();
        oneOf.add(original);
        oneOf.add(nullBranch);

        Schema<?> wrapper = new Schema<>();
        wrapper.setOneOf(oneOf);
        if (original.getDescription() != null) {
            wrapper.setDescription(original.getDescription());
        }
        props.put(property, wrapper);
    }
}
