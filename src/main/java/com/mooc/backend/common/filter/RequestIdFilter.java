package com.mooc.backend.common.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 跨切关注点：每请求生成 UUID 作为 {@code request_id}。
 *
 * <p>写入 request attribute（供 controller 按需取用）与 SLF4J {@link MDC}（供日志与
 * {@code BaseResponse} 构造器读取），响应完成后清理 MDC 防止线程复用串号。
 * 以最高优先级注册，确保在 Spring Security 过滤器链之前即注入，全链路可用。
 */
@Component
public class RequestIdFilter implements Filter, Ordered {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String requestId = UUID.randomUUID().toString();
        try {
            request.setAttribute("requestId", requestId);
            MDC.put("requestId", requestId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
