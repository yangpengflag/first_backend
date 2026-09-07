package com.mooc.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 私信模块配置，绑定 {@code messaging.rate-limit.*}。
 *
 * <p>发送限流复用 {@code auth.ratelimit.RateLimiter} 组件（仅 import，不修改 auth 包），
 * 阈值由本模块自有配置承载（design.md：10 条/分钟起步，常量可配）。
 */
@ConfigurationProperties(prefix = "messaging.rate-limit")
public class MessagingProperties {

    /** 私信发送：单用户每分钟上限。 */
    private int sendPerUserPerMinute = 10;

    public int getSendPerUserPerMinute() {
        return sendPerUserPerMinute;
    }

    public void setSendPerUserPerMinute(int sendPerUserPerMinute) {
        this.sendPerUserPerMinute = sendPerUserPerMinute;
    }
}
