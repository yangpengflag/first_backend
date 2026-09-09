package com.mooc.backend.service.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切块器契约（change: ai-rag，tasks 3.1 / design.md D4）：空文本空列表、短文单块、
 * 多段聚合不超限、超长段按词/码点断行、中英文不切坏字符（不产生孤立代理对）。
 */
class KnowledgeDocumentChunkerTest {

    @Test
    void blankAndNullYieldEmpty() {
        assertThat(KnowledgeDocumentChunker.chunk(null, 100)).isEmpty();
        assertThat(KnowledgeDocumentChunker.chunk("   \n  ", 100)).isEmpty();
    }

    @Test
    void shortTextStaysSingleTrimmedChunk() {
        assertThat(KnowledgeDocumentChunker.chunk("  hello world  ", 100))
                .containsExactly("hello world");
    }

    @Test
    void paragraphsAreGroupedUpToLimit() {
        String text = "Para one sentence. ".repeat(10).trim()
                + "\n\n" + "Para two sentence. ".repeat(30).trim()
                + "\n\n" + "Para three sentence. ".repeat(20).trim();
        List<String> chunks = KnowledgeDocumentChunker.chunk(text, 80);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c).isNotBlank();
            assertThat(c.length()).isLessThanOrEqualTo(80);
        });
        // 空白不敏感等价：拼接语义还原原文
        String merged = String.join("\n\n", chunks).replaceAll("\\s+", " ");
        String original = text.replaceAll("\\s+", " ");
        assertThat(merged).isEqualTo(original);
    }

    @Test
    void longEnglishLineBreaksOnWordBoundary() {
        String words = String.join(" ",
                "alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel", "india", "juliet");
        List<String> chunks = KnowledgeDocumentChunker.chunk(words, 12);

        assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(12));
        // 不以空格开头，且不切词：按空格拼接应还原
        assertThat(String.join(" ", chunks)).isEqualTo(words);
    }

    @Test
    void chineseAndSurrogatesAreNeverSplit() {
        String chinese = "熊猫基地是一个非常受欢迎的景点，可以看到很多大熊猫在竹林里玩耍。".repeat(6);
        String emoji = "😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀";
        List<String> chunks = KnowledgeDocumentChunker.chunk(chinese + "\n" + emoji, 30);

        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.length()).isLessThanOrEqualTo(30);
            // 不含孤立代理对（切块不得落在 emoji 两个 code unit 之间）
            boolean highPending = false;
            for (int i = 0; i < c.length(); i++) {
                char ch = c.charAt(i);
                if (highPending) {
                    assertThat(Character.isLowSurrogate(ch)).isTrue();
                    highPending = false;
                } else if (Character.isHighSurrogate(ch)) {
                    highPending = true;
                } else {
                    assertThat(Character.isSurrogate(ch)).isFalse();
                }
            }
            assertThat(highPending).isFalse();
        });
    }

    @Test
    void singleUnbreakableHugeLineHardSplitsByCodePoint() {
        String line = "x".repeat(5000);
        List<String> chunks = KnowledgeDocumentChunker.chunk(line, 1000);
        assertThat(chunks).hasSize(5);
        assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isEqualTo(1000));
    }
}
