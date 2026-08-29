package com.mooc.backend.auth.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可推进时钟。
 *
 * <p>锁定窗口、验证码过期等行为依赖「当前时间」，固定时钟让这些断言完全确定，
 * 无需真实等待 15 分钟或 24 小时。
 */
public class MutableClock extends Clock {

    private Instant now;

    public MutableClock(Instant now) {
        this.now = now;
    }

    public void setNow(Instant now) {
        this.now = now;
    }

    public void advance(Duration duration) {
        this.now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
