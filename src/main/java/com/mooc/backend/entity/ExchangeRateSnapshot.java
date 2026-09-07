package com.mooc.backend.entity;

import com.mooc.backend.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 汇率 last-good 快照（change: add-travel-services，task 3.4）。
 *
 * <p><b>单行表，覆盖写。</b> 主键固定为 {@link #SINGLETON_ID}——这份数据是全站单例
 * （出网固定 {@code base=CNY} 一份，见 design §4「不按目标币种拆 key」），不存历史。
 * 存历史意味着要定期清理、要选「哪一行是当前」，而没有任何功能读第二行。
 *
 * <p><b>存在的理由只有一个：Redis 冷启动</b>（design §2）。Redis 是可重建的读缓存，重启即空；
 * 汇率影响全站每一个价格展示，静默消失的代价远大于一张两列表。天气没有这张表——
 * 24h 前的预报存下来也没用。
 *
 * <p><b>两个时间戳，不是一个</b>（design §2.1）。它们会合法地不相等：ECB 周末与假日不发布，
 * 周日刷新会成功拿到周五的牌价。此时数据是新鲜的（{@code fetchedAt} 是刚才）而牌价是三天前的
 * （{@code upstreamDate} 是周五）。只存一个字段，要么周末误判 stale，要么向用户谎称牌价是今天的。
 *
 * <p>{@code rates} 以 JSON 文本落 {@code TEXT} 列，不建 30 行的子表：这张表唯一的读者是
 * 「整表回填 Redis」，拆成子表只会给一个没人做的查询加一次 join。
 */
@Entity
@Table(name = "exchange_rate_snapshots")
public class ExchangeRateSnapshot extends BaseEntity {

    /**
     * 单行表的固定主键。用常量而非「取第一行」：`findAll().get(0)` 在并发插入下会出现两行，
     * 而 `save` 一个固定 id 是天然的 upsert。
     */
    public static final UUID SINGLETON_ID = UUID.fromString("00000000-0000-0000-0000-00000000cafe");

    /** 基准币种，取自上游响应（对得上才说明拿到的是想要的表）。 */
    @Column(name = "base", nullable = false, length = 8)
    private String base;

    /**
     * 上游 {@code date}，即这份牌价所属日期（= 对外的 {@code as_of}）。
     * <b>仅用于展示，不参与 TTL 判定</b>——判定一律用 {@link #fetchedAt}。
     */
    @Column(name = "upstream_date", nullable = false)
    private LocalDate upstreamDate;

    /**
     * 我们成功拉到这份数据的时刻。<b>soft / hard TTL 判定的唯一依据。</b>
     * 与 {@code BaseEntity.updatedAt} 不同：后者是审计字段，任何字段变更都会刷新它；
     * 本字段的语义是「上游数据的获取时刻」，两者在覆盖写时恰好相等纯属巧合，不可互相替代。
     */
    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    /** 汇率表的 JSON 文本，形如 {@code {"USD":0.14901,...}}。保证非空（空表在客户端就当失败）。 */
    @Column(name = "rates_json", columnDefinition = "TEXT", nullable = false)
    private String ratesJson;

    protected ExchangeRateSnapshot() {
        // JPA only
    }

    private ExchangeRateSnapshot(String base, LocalDate upstreamDate, Instant fetchedAt,
                                 String ratesJson, Instant now) {
        super(SINGLETON_ID, now);
        this.base = base;
        this.upstreamDate = upstreamDate;
        this.fetchedAt = fetchedAt;
        this.ratesJson = ratesJson;
    }

    /**
     * 建一份新快照。时间由调用方注入（照 {@code BaseEntity} 与 {@code User} 的既有约定），
     * 让 TTL 相关行为在测试中完全可控。
     */
    public static ExchangeRateSnapshot create(String base, LocalDate upstreamDate, Instant fetchedAt,
                                              String ratesJson, Instant now) {
        return new ExchangeRateSnapshot(base, upstreamDate, fetchedAt, ratesJson, now);
    }

    /**
     * 覆盖写：主键不变，四个值全换。刷新任务每轮成功都调它，故这是本实体唯一的写路径。
     * 不做「值没变就跳过」的优化——{@code fetchedAt} 每轮都在变，它正是 TTL 判定的依据。
     */
    public void overwrite(String base, LocalDate upstreamDate, Instant fetchedAt,
                          String ratesJson, Instant now) {
        this.base = base;
        this.upstreamDate = upstreamDate;
        this.fetchedAt = fetchedAt;
        this.ratesJson = ratesJson;
        this.touch(now);
    }

    public String getBase() {
        return base;
    }

    public LocalDate getUpstreamDate() {
        return upstreamDate;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public String getRatesJson() {
        return ratesJson;
    }
}
