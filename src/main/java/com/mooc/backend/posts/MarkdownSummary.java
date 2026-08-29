package com.mooc.backend.posts;

/**
 * 由 Markdown 源文本派生纯文本摘要的纯函数工具。
 *
 * <p>策略：剥离代码块 / 链接 / 图片 / 标题与强调符号等 Markdown 语法，折叠空白，
 * 取前 {@code MAX} 字符（中文按 code point 计）。用于列表卡片展示，不影响数据正确性，
 * 故不引入完整 Markdown 解析库。
 */
public final class MarkdownSummary {

    private static final int MAX = 160;

    private MarkdownSummary() {
    }

    public static String derive(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        String stripped = markdown
                .replaceAll("(?s)```.*?```", " ")          // 代码块
                .replaceAll("(?s)>.*?(?=\\n|$)", " ")        // 引用块
                .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ") // 图片
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1") // 链接 → 锚文本
                .replaceAll("(?m)^#{1,6}\\s*", " ")          // 标题 #
                .replaceAll("[*_~`>#-]+", " ")               // 强调 / 符号
                .replaceAll("\\s+", " ")
                .trim();
        return stripped.length() <= MAX ? stripped : stripped.substring(0, MAX);
    }
}
