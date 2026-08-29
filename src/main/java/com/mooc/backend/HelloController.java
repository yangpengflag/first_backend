package com.mooc.backend;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 脚手架遗留的健康检查端点。
 *
 * <p>与本 change 的业务无关，保持其原有可访问性（已在 {@code SecurityConfig} 放行），
 * 仅补充 OpenAPI 注解使其出现在文档中。
 */
@Tag(name = "健康检查", description = "服务可用性探针")
// produces 显式声明：避免 springdoc 把响应 content type 推断为通配符。
@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class HelloController {

    @Operation(summary = "健康检查", description = "返回固定文案，用于确认后端进程存活。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "服务正常")
    })
    @GetMapping("/hello")
    public Map<String, String> hello() {
        return Map.of("message", "Hello from Spring Boot!", "status", "ok");
    }
}
