package com.mooc.backend.service;

import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 助手提示词契约（change: ai-rag D9 + ai-spot-tools task 3.2）：persona 逐字固定且含全部工具指引；
 * 知识区段是<b>独立追加</b>，绝不改写 persona（身份 / 语言规则 / 工具指引）。
 */
class AiPromptsTest {

    private static Document knowledge() {
        return new Document("spot:chengdu-giant-panda-base:0", "Opens 07:30.",
                Map.of("type", "spot", "nameEn", "Giant Panda Base",
                        "url", "/spots/chengdu-giant-panda-base"));
    }

    @Test
    void personaPinsIdentityLanguageRulesAndEveryTool() {
        assertThat(AiPrompts.PERSONA_PROMPT)
                .contains("You are the WanderChina AI travel planner")
                .contains("Respond in English by default")
                // 四个工具全部在指引里，模型才知道自己能查什么
                .contains("get_city_weather")
                .contains("get_exchange_rates")
                .contains("search_spots")
                .contains("get_spot_details");
    }

    @Test
    void personaStatesToolVersusKnowledgeDivisionOfLabour() {
        // design.md D3：列表类问法先调 search_spots（保证点位真实存在），描述性事实优先用知识区段。
        // task 4.1 实测：原措辞下模型对 "hidden gems" 不调工具（RAG 兜底），故强化为"先查再答"。
        assertThat(AiPrompts.PERSONA_PROMPT)
                .contains("call search_spots first")
                .contains("prefer the site knowledge provided below");
    }

    @Test
    void knowledgeDoesNotRewritePersonaOrToolGuidance() {
        String system = AiPrompts.buildSystemPrompt(List.of(knowledge()));

        assertThat(system).startsWith(AiPrompts.PERSONA_PROMPT);
        assertThat(system).contains("get_city_weather");
        assertThat(system).contains("search_spots");
        assertThat(system).contains("# WanderChina site knowledge");
        // 知识区段必须位于 persona 与工具指引之后，不插进 persona 内部
        assertThat(system.indexOf("# WanderChina site knowledge"))
                .isGreaterThan(system.indexOf("get_spot_details"));
    }

    @Test
    void noKnowledgeMeansPersonaPlusCitationRulesOnly() {
        String system = AiPrompts.buildSystemPrompt(List.of());

        assertThat(system).startsWith(AiPrompts.PERSONA_PROMPT);
        assertThat(system).contains(AiPrompts.CITATION_RULES);
        assertThat(system).doesNotContain("# WanderChina site knowledge");
    }
}
