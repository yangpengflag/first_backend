package com.mooc.backend.repository;

/**
 * LIKE 模式辅助（change: ai-semantic-search，design.md D3）。
 *
 * <p>用户输入直接进 native SQL 的 {@code LIKE} 时，{@code %} / {@code _} 会被当通配符解释，
 * 反斜杠本身也需要转义。本工具把原始输入转义为字面量模式，杜绝"搜索 <code>A_B</code> 命中 <code>AXB</code>"
 * 一类通配符注入。配对使用 SQL 端 {@code ESCAPE '\\'}。
 */
public final class LikePatterns {

    private LikePatterns() {
    }

    /** 转义 LIKE 通配符（反斜杠必须最先处理），返回可与 {@code ESCAPE '\\'} 配对的模式。 */
    public static String escape(String q) {
        return q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 生成 {@code %escaped%} 的包含匹配模式。 */
    public static String contains(String q) {
        return "%" + escape(q) + "%";
    }
}
