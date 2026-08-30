package com.mooc.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.MDC;

/**
 * 所有响应 DTO 的公共基类，自带 {@code request_id}。
 *
 * <p>{@code request_id} 由 {@code RequestIdFilter} 每请求生成并写入 MDC，
 * 本构造器在构造时从 MDC 读取——因此无论 Response 在 service 还是 controller 层构造，
 * 都能自动携带请求级关联 ID，无需逐层透传。
 */
public abstract class BaseResponse {

    @JsonProperty("request_id")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private final String requestId;

    protected BaseResponse() {
        this.requestId = MDC.get("requestId");
    }

    public String getRequestId() {
        return requestId;
    }
}
