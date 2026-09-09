package com.mooc.backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 写作辅助响应（change: ai-post-assist，tasks 3.1 / design.md D1）。
 *
 * <p>继承 {@code BaseResponse} 自动携带顶层 {@code request_id}。按 {@code kind} 取用字段：
 * title / tags / content 三者互斥填充，未产出者以 {@code NON_NULL} 省略（表达「该 kind 无结果」）。
 *
 * @param kind     echo 请求的动作类型
 * @param title    标题建议（kind=title）
 * @param tags     标签建议（kind=tags）
 * @param content  润色后正文（kind=polish）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiAssistResponse extends BaseResponse {

    @JsonProperty("kind")
    private final String kind;

    @JsonProperty("title")
    private final String title;

    @JsonProperty("tags")
    private final List<String> tags;

    @JsonProperty("content")
    private final String content;

    public AiAssistResponse(String kind, String title, List<String> tags, String content) {
        super();
        this.kind = kind;
        this.title = title;
        this.tags = tags;
        this.content = content;
    }

    public String getKind() {
        return kind;
    }

    public String getTitle() {
        return title;
    }

    public List<String> getTags() {
        return tags;
    }

    public String getContent() {
        return content;
    }
}
