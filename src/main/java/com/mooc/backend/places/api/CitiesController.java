package com.mooc.backend.places.api;

import com.mooc.backend.places.service.CityService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 城市目的地 HTTP 接口（只读，公开免鉴权）。
 *
 * <p>列表仅按 {@code name} 升序 + {@code page}/{@code size} 分页（省份 / 标签 / sort 已随
 * {@code city-module} 精简移除）。错误经 {@code GlobalExceptionHandler}。
 */
@RestController
@RequestMapping(value = "/api/cities", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "城市目的地", description = "中国城市目的地的公开列表与详情（含 Top POI 与相关攻略占位）。")
public class CitiesController {

    private final CityService cityService;

    public CitiesController(CityService cityService) {
        this.cityService = cityService;
    }

    @Operation(summary = "城市列表", description = "按 name 升序分页返回全部存活城市（page/size 分页）。")
    @GetMapping
    public ResponseEntity<CityListResponse> list(
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ResponseEntity.ok(cityService.list(page, size));
    }

    @Operation(summary = "城市详情", description = "返回城市介绍、Top POI 与相关攻略占位。")
    @GetMapping("/{slug}")
    public ResponseEntity<CityDetail> get(@PathVariable String slug) {
        return ResponseEntity.ok(cityService.getBySlug(slug));
    }
}
