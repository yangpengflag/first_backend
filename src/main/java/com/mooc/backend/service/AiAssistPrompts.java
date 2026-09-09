package com.mooc.backend.service;

/**
 * 写作辅助提示词（change: ai-post-assist，tasks 2.1 / design.md D4）。
 *
 * <p>三种 {@code kind} 的系统提示为<b>编译期常量、逐字固定</b>、用户不可控（防注入：用户输入只进
 * user 角色消息，绝不与 system 拼接、绝不被当作指令）。断言逐字固定见 {@code AiAssistServiceTest}。
 * 角色统一为「英文旅行写作编辑」，只输出所要求的格式、不引入新事实、不对用户正文做指令性解读。
 */
public final class AiAssistPrompts {

    private AiAssistPrompts() {
    }

    /** 标题：单行纯英文标题，无 Markdown / 无引号 / 无解释。 */
    public static final String TITLE_SYSTEM_PROMPT = """
            You are the WanderChina travel writing editor. Your task is to write a single, \
            catchy English title for a travel guide article.

            Rules:
            - Output ONLY the title, on a single line.
            - Do NOT wrap it in quotes or Markdown.
            - Do NOT add explanations, prefixes, or suffixes.
            - Keep it concise and appealing to international visitors to China.
            """;

    /** 标签：纯 JSON 数组，英文小写，无解释。 */
    public static final String TAGS_SYSTEM_PROMPT = """
            You are the WanderChina content tagging assistant. Given the article, suggest up to 10 \
            relevant English tags.

            Rules:
            - Output ONLY a JSON array of strings, for example ["great-wall", "beijing-food", "hiking"].
            - Use lowercase English words separated by hyphens.
            - Do NOT output anything other than the JSON array (no explanation, no markdown, no code fences).
            """;

    /** 润色：保持输入语言与 Markdown 结构及事实，只输出润色后正文，无前后缀 / 解释。 */
    public static final String POLISH_SYSTEM_PROMPT = """
            You are the WanderChina travel writing editor. Polish the user's article to improve clarity, \
            grammar, and flow.

            Rules:
            - Keep the SAME language as the input (do not translate).
            - Preserve the original Markdown structure and all facts. Do not add or remove information.
            - Output ONLY the polished text. No explanations, no prefixes, no suffixes, no "Here is ...".
            """;
}
