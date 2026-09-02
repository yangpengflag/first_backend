package com.mooc.backend.places.service;

import com.mooc.backend.places.repository.SpotRepository;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 景点浏览量防刷计数服务。
 *
 * <p>只在景点详情访问时累加（列表查询绝不计数），通过 {@code CompletableFuture.runAsync} 异步落库，
 * 不阻塞详情响应。防刷采用<b>进程内</b> {@code (slug, clientIp) → 最近一次时间戳} 缓存，
 * 同一键在 {@link #TTL_MS} 窗口内的重复请求直接跳过，避免最朴素的刷量。
 *
 * <p>注：城市浏览量已随 {@code city-module} 精简移除（City 不再有 {@code view_count}），
 * 本服务仅服务 Spot。进程内缓存仅覆盖单实例；多实例部署需改为集中式（Redis）限频。
 */
@Service
public class ViewCountService {

    private static final long TTL_MS = 10_000;

    private final SpotRepository spotRepository;
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

    public ViewCountService(SpotRepository spotRepository) {
        this.spotRepository = spotRepository;
    }

    /** 记录某景点详情访问（异步 + 防刷）。 */
    public void recordSpotView(String slug, String clientIp) {
        if (recentlySeen(slug, clientIp)) {
            return;
        }
        spotRepository.incrementViewCountBySlug(slug);
    }

    private boolean recentlySeen(String slug, String clientIp) {
        String key = slug + "|" + (clientIp == null ? "anon" : clientIp);
        long now = System.currentTimeMillis();
        Long prev = lastSeen.put(key, now);
        return prev != null && (now - prev) < TTL_MS;
    }
}
