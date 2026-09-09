package com.mooc.backend.service;

import com.mooc.backend.config.AiAssistProperties;
import com.mooc.backend.dto.AiAssistRequest;
import com.mooc.backend.exception.AiAssistValidationException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 写作辅助服务（change: ai-post-assist，tasks 2.2 / 2.3 / design.md D2–D5）。
 *
 * <p>三种动作（title / tags / polish）共享同一管线：长度护栏 → 一次同步生成 → 按 kind 裁剪。
 * 模型调用统一走窄接口 {@link AiTextGenerator}（未装配 → {@code AiChatUnavailableException} 由
 * 上层转 503）；本服务只做纯文本处理，零出网、零 mock 依赖，单测以 Fake 覆盖。
 *
 * <p>护栏要点（design.md D5）：
 * <ul>
 *   <li>title / tags 的 content 超 {@code maxInputChars} → <b>服务端截断</b>后送模型（只损质量不损数据）；</li>
 *   <li>polish 的 content 超 {@code polishMaxChars} → <b>直接拒绝</b>（抛 {@link AiAssistValidationException}，绝不截断，防静默删稿）；</li>
 *   <li>模型产出空白 → 视为<b>无结果</b>返回空字段（200 语义），不是异常。</li>
 * </ul>
 */
@Service
public class AiAssistService {

    private static final Logger log = LoggerFactory.getLogger(AiAssistService.class);

    private final AiTextGenerator textGenerator;
    private final AiAssistProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiAssistService(AiTextGenerator textGenerator, AiAssistProperties properties) {
        this.textGenerator = textGenerator;
        this.properties = properties;
    }

    /**
     * 按 kind 产出一条建议。
     *
     * @param kind    title / tags / polish
     * @param title   可选原标题草稿（仅 title 使用）
     * @param content 正文（必填，已通过 DTO 短 / 长校验）
     * @return 仅对应 kind 字段非空的 {@link AiAssistSuggestion}
     */
    public AiAssistSuggestion suggest(AiAssistRequest.Kind kind, String title, String content) {
        // 分档最短正文长度：tags 仅需非空、title ≥ 5、polish ≥ 20（见 ai-post-assist spec）。
        // DTO 仅保证非空（@NotBlank + min=1）；随 kind 变化的最短长度在此按 kind 判定。
        int min = minContentFor(kind);
        if (content == null || content.trim().length() < min) {
            throw new AiAssistValidationException(minContentMessage(kind, min));
        }

        // polish 超长 → 拒绝（绝不截断，避免静默删除用户未润色的部分）。
        if (kind == AiAssistRequest.Kind.polish && content.length() > properties.polishMaxChars()) {
            throw new AiAssistValidationException(
                    "Polish content must be at most " + properties.polishMaxChars() + " characters.");
        }

        // title / tags 超长 → 截断后送模型（只留计数日志，不落正文）。
        String modelInput = content;
        if (content.length() > properties.maxInputChars()) {
            int dropped = content.length() - properties.maxInputChars();
            modelInput = content.substring(0, properties.maxInputChars());
            log.info("AiAssist {} input truncated by {} chars (maxInputChars={})",
                    kind, dropped, properties.maxInputChars());
        }

        String system = systemPromptFor(kind);
        String user = userPromptFor(kind, title, modelInput);
        String raw = textGenerator.generate(system, user);

        // 空产出 → 无结果（200 空字段），不是异常。
        if (raw == null || raw.isBlank()) {
            return new AiAssistSuggestion(null, null, null);
        }

        return switch (kind) {
            case title -> new AiAssistSuggestion(emptyToNull(cleanTitle(raw)), null, null);
            case tags -> new AiAssistSuggestion(null, emptyToNullList(parseAndNormalizeTags(raw)), null);
            case polish -> new AiAssistSuggestion(null, null, emptyToNull(cleanPolish(raw)));
        };
    }

    /** 分档最短正文长度：tags ≥ 1 / title ≥ 5 / polish ≥ 20（常量，非配置项）。 */
    private static int minContentFor(AiAssistRequest.Kind kind) {
        return switch (kind) {
            case tags -> 1;
            case title -> 5;
            case polish -> 20;
        };
    }

    private static String minContentMessage(AiAssistRequest.Kind kind, int min) {
        String label = switch (kind) {
            case tags -> "Tags";
            case title -> "Title";
            case polish -> "Polish";
        };
        return "Content for " + label + " must be at least " + min + " character" + (min == 1 ? "" : "s") + ".";
    }

    private String systemPromptFor(AiAssistRequest.Kind kind) {
        return switch (kind) {
            case title -> AiAssistPrompts.TITLE_SYSTEM_PROMPT;
            case tags -> AiAssistPrompts.TAGS_SYSTEM_PROMPT;
            case polish -> AiAssistPrompts.POLISH_SYSTEM_PROMPT;
        };
    }

    private String userPromptFor(AiAssistRequest.Kind kind, String title, String modelInput) {
        return switch (kind) {
            case title -> {
                StringBuilder sb = new StringBuilder();
                if (title != null && !title.isBlank()) {
                    sb.append("Draft title: ").append(title).append("\n\n");
                }
                sb.append("Article content:\n").append(modelInput);
                yield sb.toString();
            }
            case tags -> "Article content:\n" + modelInput;
            case polish -> modelInput;
        };
    }

    // ---- 输出裁剪（design.md D4）----

    /** 取首行、剥离包裹引号 / # / * / 反引号、trim、> titleMaxChars 截断。 */
    private String cleanTitle(String raw) {
        String line = raw.strip();
        int nl = line.indexOf('\n');
        if (nl >= 0) {
            line = line.substring(0, nl);
        }
        line = line.strip();

        boolean changed = true;
        while (changed) {
            changed = false;
            if (line.length() >= 2) {
                char first = line.charAt(0);
                char last = line.charAt(line.length() - 1);
                if (first == last && (first == '"' || first == '\'')) {
                    line = line.substring(1, line.length() - 1).strip();
                    changed = true;
                    continue;
                }
            }
            if (line.startsWith("#") || line.startsWith("*") || line.startsWith("`")) {
                line = line.substring(1).strip();
                changed = true;
                continue;
            }
            if (line.endsWith("#") || line.endsWith("*") || line.endsWith("`")) {
                line = line.substring(0, line.length() - 1).strip();
                changed = true;
            }
        }

        if (line.length() > properties.titleMaxChars()) {
            line = line.substring(0, properties.titleMaxChars());
        }
        return line;
    }

    /** 优先 JSON 数组解析，失败按 , / 全角逗号 / 换行降级切分，再归一化（与帖子落库口径一致）。 */
    private List<String> parseAndNormalizeTags(String raw) {
        String text = raw.strip();
        List<String> rawTags;
        if (text.startsWith("[")) {
            try {
                rawTags = parseJsonArray(text);
            } catch (Exception e) {
                rawTags = fallbackSplit(text);
            }
        } else {
            rawTags = fallbackSplit(text);
        }
        return normalizeTags(rawTags);
    }

    private List<String> parseJsonArray(String text) throws Exception {
        List<?> list = objectMapper.readValue(text, new TypeReference<List<?>>() {});
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o != null) {
                out.add(o.toString());
            }
        }
        return out;
    }

    private List<String> fallbackSplit(String text) {
        return Arrays.asList(text.split("[,，\\n\\r]+"));
    }

    /**
     * 标签归一化：trim → 小写(Locale.ROOT) → 去空 → 去超长(≤30) → 去重 → 上限(10)。
     * 与 {@code PostService.normalizeTags} 逐字一致（含 ≤30 上限），保证建议标签提交即通过。
     */
    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream()
                .filter(Objects::nonNull)
                .map(t -> t.trim().toLowerCase(Locale.ROOT))
                .filter(t -> !t.isEmpty())
                .filter(t -> t.length() <= 30)
                .distinct()
                .limit(10)
                .toList();
    }

    /** 润色：仅去首尾空白、不做内容截断；输出异常放大（> 3×上限）视为无结果。 */
    private String cleanPolish(String raw) {
        String trimmed = raw.strip();
        if (trimmed.length() > 3 * properties.polishMaxChars()) {
            log.warn("AiAssist polish output abnormally large ({} chars); treated as no result",
                    trimmed.length());
            return "";
        }
        return trimmed;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static List<String> emptyToNullList(List<String> tags) {
        return (tags == null || tags.isEmpty()) ? null : tags;
    }
}
