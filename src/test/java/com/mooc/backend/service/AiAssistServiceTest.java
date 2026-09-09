package com.mooc.backend.service;

import com.mooc.backend.config.AiAssistProperties;
import com.mooc.backend.dto.AiAssistRequest;
import com.mooc.backend.exception.AiAssistValidationException;
import com.mooc.backend.exception.AiChatUnavailableException;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 写作辅助服务单测（tasks 2.2 / 2.3 / design.md D4 / D5）：用 Fake {@link AiTextGenerator}
 * 覆盖全部裁剪 / 归一化 / 截断 / 空结果 / 未装配逻辑，零出网、零 mock 框架。
 */
class AiAssistServiceTest {

    private static final AiAssistProperties DEFAULTS = new AiAssistProperties(
            12_000, 8_000, 200,
            new AiAssistProperties.RateLimit(20, 10),
            new AiAssistProperties.Timeout(5_000, 30_000));

    private static AiAssistService serviceWith(AiTextGenerator gen, AiAssistProperties props) {
        return new AiAssistService(gen, props);
    }

    private static AiAssistService serviceWith(AiTextGenerator gen) {
        return serviceWith(gen, DEFAULTS);
    }

    // ---- title 裁剪 ----

    @Test
    void titleStripsWrappingDoubleQuotes() {
        Fake gen = new Fake("\"Great Wall Adventure\"");
        AiAssistSuggestion s = serviceWith(gen).suggest(AiAssistRequest.Kind.title, null, content());
        assertThat(s.title()).isEqualTo("Great Wall Adventure");
    }

    @Test
    void titleStripsMarkdownHeading() {
        Fake gen = new Fake("# Great Wall Adventure");
        AiAssistSuggestion s = serviceWith(gen).suggest(AiAssistRequest.Kind.title, null, content());
        assertThat(s.title()).isEqualTo("Great Wall Adventure");
    }

    @Test
    void titleStripsBoldMarkers() {
        Fake gen = new Fake("**Great Wall Adventure**");
        AiAssistSuggestion s = serviceWith(gen).suggest(AiAssistRequest.Kind.title, null, content());
        assertThat(s.title()).isEqualTo("Great Wall Adventure");
    }

    @Test
    void titleStripsBackticks() {
        Fake gen = new Fake("`Great Wall Adventure`");
        AiAssistSuggestion s = serviceWith(gen).suggest(AiAssistRequest.Kind.title, null, content());
        assertThat(s.title()).isEqualTo("Great Wall Adventure");
    }

    @Test
    void titleTakesFirstLineOnly() {
        Fake gen = new Fake("First Line Title\nSecond line ignored");
        AiAssistSuggestion s = serviceWith(gen).suggest(AiAssistRequest.Kind.title, null, content());
        assertThat(s.title()).isEqualTo("First Line Title");
    }

    @Test
    void titleTruncatesOverTitleMaxChars() {
        String longTitle = "x".repeat(500);
        Fake gen = new Fake(longTitle);
        AiAssistSuggestion s = serviceWith(gen).suggest(AiAssistRequest.Kind.title, null, content());
        assertThat(s.title()).hasSize(200);
    }

    // ---- tags 解析 / 归一化 ----

    @Test
    void tagsParseJsonArrayAndNormalize() {
        Fake gen = new Fake("[\"Hidden Gem\", \"HIKING\", \"hiking\", \"  \", \"paris\"]");
        AiAssistSuggestion s = serviceWith(gen).suggest(AiAssistRequest.Kind.tags, null, content());
        assertThat(s.tags()).containsExactly("hidden gem", "hiking", "paris");
    }

    @Test
    void tagsFallbackSplitOnCommaWhenNotJson() {
        Fake gen = new Fake("hiking, food, beijing");
        AiAssistSuggestion s = serviceWith(gen).suggest(AiAssistRequest.Kind.tags, null, content());
        assertThat(s.tags()).containsExactly("hiking", "food", "beijing");
    }

    @Test
    void tagsDropOverLongAndCapAtTen() {
        List<String> raw = java.util.stream.IntStream.range(0, 15)
                .mapToObj(i -> "tag-" + i)
                .toList();
        String json = "[" + String.join(",", raw.stream().map(t -> "\"" + t + "\"").toList()) + "]";
        Fake gen = new Fake(json);
        AiAssistSuggestion s = serviceWith(gen).suggest(AiAssistRequest.Kind.tags, null, content());
        // 15 个均 ≤30 且唯一 → 上限 10
        assertThat(s.tags()).hasSize(10);
        assertThat(s.tags()).doesNotContain("tag-10"); // 超出部分被 limit(10) 截断
    }

    @Test
    void tagsRemoveOverThirtyCharItems() {
        Fake gen = new Fake("[\"Hidden Gem\", \"a-very-long-tag-name-that-exceeds-thirty-characters\"]");
        AiAssistSuggestion s = serviceWith(gen).suggest(AiAssistRequest.Kind.tags, null, content());
        assertThat(s.tags()).containsExactly("hidden gem");
    }

    // ---- polish ----

    @Test
    void polishOnlyStripsWhitespaceAndKeepsContent() {
        String body = "# Heading\n\nSome **bold** text with 中文 and Markdown.";
        Fake gen = new Fake("   " + body + "   \n");
        AiAssistSuggestion s = serviceWith(gen).suggest(AiAssistRequest.Kind.polish, null, body);
        assertThat(s.content()).isEqualTo(body);
    }

    @Test
    void polishOverLimitIsRejectedWithoutModelCall() {
        AiAssistProperties props = new AiAssistProperties(
                12_000, 8_000, 200,
                new AiAssistProperties.RateLimit(20, 10),
                new AiAssistProperties.Timeout(5_000, 30_000));
        Fake gen = new Fake("should not be called");
        AiAssistService service = serviceWith(gen, props);
        String tooLong = "x".repeat(props.polishMaxChars() + 1);

        assertThatThrownBy(() -> service.suggest(AiAssistRequest.Kind.polish, null, tooLong))
                .isInstanceOf(AiAssistValidationException.class);
        assertThat(gen.callCount).isZero();
    }

    // ---- 长度护栏：title / tags 截断 ----

    @Test
    void titleInputOverMaxIsTruncatedBeforeModelCall() {
        AiAssistProperties props = new AiAssistProperties(
                10, 8_000, 200,
                new AiAssistProperties.RateLimit(20, 10),
                new AiAssistProperties.Timeout(5_000, 30_000));
        Fake gen = new Fake("ok");
        serviceWith(gen, props).suggest(AiAssistRequest.Kind.title, null, "x".repeat(50));
        // 送模型的 user message 内含被截断到 10 字符的正文
        assertThat(gen.lastUser).contains("xxxxxxxxxx");
        assertThat(gen.lastUser).doesNotContain("xxxxxxxxxxx"); // 第 11 个字符未进入
    }

    @Test
    void tagsInputOverMaxIsTruncatedBeforeModelCall() {
        AiAssistProperties props = new AiAssistProperties(
                10, 8_000, 200,
                new AiAssistProperties.RateLimit(20, 10),
                new AiAssistProperties.Timeout(5_000, 30_000));
        Fake gen = new Fake("[\"kept\"]");
        serviceWith(gen, props).suggest(AiAssistRequest.Kind.tags, null, "y".repeat(50));
        assertThat(gen.lastUser).contains("yyyyyyyyyy");
        assertThat(gen.lastUser).doesNotContain("yyyyyyyyyyy");
    }

    // ---- 空结果语义 ----

    @Test
    void blankModelOutputYieldsEmptyResultNotException() {
        Fake gen = new Fake("   ");
        AiAssistSuggestion s = serviceWith(gen).suggest(AiAssistRequest.Kind.title, null, content());
        assertThat(s.title()).isNull();
        assertThat(s.tags()).isNull();
        assertThat(s.content()).isNull();
    }

    @Test
    void emptyTagsArrayYieldsNullTagsNotException() {
        Fake gen = new Fake("[]");
        AiAssistSuggestion s = serviceWith(gen).suggest(AiAssistRequest.Kind.tags, null, content());
        assertThat(s.tags()).isNull();
    }

    // ---- 未装配 ----

    @Test
    void unconfiguredGeneratorPropagatesAiChatUnavailable() {
        AiTextGenerator failing = (sys, usr) -> {
            throw new AiChatUnavailableException();
        };
        assertThatThrownBy(() ->
                serviceWith(failing).suggest(AiAssistRequest.Kind.title, null, content()))
                .isInstanceOf(AiChatUnavailableException.class);
    }

    // ---- 对拍：service 产出的标签喂给 PostService.normalizeTags 不变（tasks 2.3）----

    @Test
    void producedTagsAreStableUnderPostServiceNormalize() {
        Fake gen = new Fake("[\"Hidden Gem\", \"HIKING\", \"hiking\", \"  \", \"paris\", \"Tokyo\", \"beijing\"]");
        AiAssistSuggestion s = serviceWith(gen).suggest(AiAssistRequest.Kind.tags, null, content());
        // 全部 ≤30，故经 PostService.normalizeTags 再度归一化结果不变
        List<String> reparsed = PostService.normalizeTags(s.tags());
        assertThat(reparsed).isEqualTo(s.tags());
    }

    // ---- 分档最短正文长度（ai-post-assist-relax-min-length）----

    @Test
    void tagsAcceptVeryShortContent() {
        Fake gen = new Fake("[\"trip\"]");
        AiAssistSuggestion s = serviceWith(gen).suggest(AiAssistRequest.Kind.tags, null, "abc");
        assertThat(s.tags()).containsExactly("trip");
    }

    @Test
    void titleShorterThanFiveCharsIsRejected() {
        Fake gen = new Fake("ignored");
        assertThatThrownBy(() -> serviceWith(gen).suggest(AiAssistRequest.Kind.title, null, "abc"))
                .isInstanceOf(AiAssistValidationException.class)
                .hasMessageContaining("Title")
                .hasMessageContaining("5");
    }

    @Test
    void polishShorterThanTwentyCharsIsRejected() {
        Fake gen = new Fake("ignored");
        assertThatThrownBy(() -> serviceWith(gen).suggest(AiAssistRequest.Kind.polish, null, "x".repeat(10)))
                .isInstanceOf(AiAssistValidationException.class)
                .hasMessageContaining("Polish")
                .hasMessageContaining("20");
    }

    @Test
    void blankContentIsRejectedForEveryKind() {
        Fake gen = new Fake("ignored");
        for (AiAssistRequest.Kind kind : AiAssistRequest.Kind.values()) {
            assertThatThrownBy(() -> serviceWith(gen).suggest(kind, null, "   "))
                    .isInstanceOf(AiAssistValidationException.class);
        }
    }

    // ---- helpers ----

    private static String content() {
        return "A long travel story about visiting China with friends and family.";
    }

    /** 记录 system / user 入参与调用次数，供截断 / 未调用断言。 */
    static class Fake implements AiTextGenerator {
        String lastSystem;
        String lastUser;
        int callCount = 0;
        private final String output;

        Fake(String output) {
            this.output = output;
        }

        @Override
        public String generate(String systemPrompt, String userPrompt) {
            this.lastSystem = systemPrompt;
            this.lastUser = userPrompt;
            this.callCount++;
            return output;
        }
    }
}
