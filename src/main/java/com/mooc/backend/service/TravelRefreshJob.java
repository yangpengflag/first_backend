package com.mooc.backend.service;

/**
 * 一轮旅行数据刷新（change: add-travel-services，task 1.4）。
 *
 * <p>抽出接口只为一件事：让 {@link TravelWarmUpListener} 能在不认识汇率 / 天气任何细节的前提下
 * 把两个任务都投递出去。新增第三类数据时实现本接口即可，预热逻辑不动。
 *
 * <p>实现方各自负责「开关关闭则直接返回」与「抢不到 {@link RefreshLock} 则跳过本轮」——
 * 这两件事的判定依赖各自的配置，放在这里会让接口反过来依赖实现的细节。
 */
public interface TravelRefreshJob {

    /** 任务名，同时是 {@link RefreshLock} 的 key 后缀（{@code rates} / {@code weather}）。 */
    String jobName();

    /**
     * 跑完整一轮刷新。<b>不得向外抛异常</b>：调用方是调度线程，异常只会进日志、且会让预热
     * 与 cron 的其余任务失去这一轮。上游失败按「跳过本轮，等下个 tick」处理。
     */
    void refresh();
}
