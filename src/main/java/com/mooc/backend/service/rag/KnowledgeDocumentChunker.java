package com.mooc.backend.service.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索文档纯函数切块器（change: ai-rag，tasks 3.1 / design.md D4）。
 *
 * <p>规则：按空行分隔的段落为单位聚合，段落合并不超过 {@code maxChars}；单段超限时按内部
 * 换行拆，再不行按词边界拆，仍超限则按 Unicode 码点硬切（绝不拆散代理对/半字）。英文/中文
 * 均不回退到句子中段（除非单行本身超限）。空文本返回空列表。
 *
 * <p>幂等性由上层文档 id（{@code type:key:seq}）负责，本类只做纯文本切分。
 */
public final class KnowledgeDocumentChunker {

    private KnowledgeDocumentChunker() {
    }

    /** 按 {@code maxChars} 切分纯文本，返回非空且各自 ≤ maxChars 的片段（已 trim，不含空段）。 */
    public static List<String> chunk(String text, int maxChars) {
        if (text == null || text.isBlank() || maxChars <= 0) {
            return List.of();
        }
        String trimmed = text.trim();
        if (trimmed.length() <= maxChars) {
            return List.of(trimmed);
        }
        String[] paragraphs = trimmed.split("\\n\\s*\\n");
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            String p = paragraph.trim();
            if (p.isEmpty()) {
                continue;
            }
            for (String unit : splitLongParagraph(p, maxChars)) {
                // 单位合并不超限则并入当前块，否则滚动到新块
                int extra = current.isEmpty() ? 0 : 1; // 合并时补回换行
                if (!current.isEmpty() && current.length() + extra + unit.length() <= maxChars) {
                    current.append('\n').append(unit);
                } else {
                    flush(out, current);
                    current.setLength(0);
                    current.append(unit);
                }
            }
        }
        flush(out, current);
        return out;
    }

    private static void flush(List<String> out, StringBuilder sb) {
        if (!sb.isEmpty()) {
            out.add(sb.toString().trim());
            sb.setLength(0);
        }
    }

    /** 单段超限：先按段内换行拆，行仍超限再按词拆，词超限按码点硬切。 */
    private static List<String> splitLongParagraph(String paragraph, int maxChars) {
        if (paragraph.length() <= maxChars) {
            return List.of(paragraph);
        }
        List<String> result = new ArrayList<>();
        for (String line : paragraph.split("\n")) {
            result.addAll(splitLongLine(line.trim(), maxChars));
        }
        return result;
    }

    private static List<String> splitLongLine(String line, int maxChars) {
        if (line.length() <= maxChars) {
            return List.of(line);
        }
        // 优先在 ≤ maxChars 的最近词边界断行；找不到词边界则按码点硬切
        int cut = line.lastIndexOf(' ', maxChars);
        if (cut > 0) {
            List<String> head = splitLongLine(line.substring(0, cut).trim(), maxChars);
            List<String> tail = splitLongLine(line.substring(cut + 1).trim(), maxChars);
            List<String> out = new ArrayList<>(head);
            out.addAll(tail);
            return out;
        }
        return hardSplit(line, maxChars);
    }

    /** 按 Unicode 码点硬切，避免切断代理对（emoji 等）。 */
    private static List<String> hardSplit(String s, int maxChars) {
        List<String> out = new ArrayList<>();
        int[] points = s.codePoints().toArray();
        int charsBudget = maxChars;
        StringBuilder piece = new StringBuilder();
        for (int point : points) {
            int len = new String(Character.toChars(point)).length();
            if (piece.length() + len > charsBudget) {
                out.add(piece.toString());
                piece.setLength(0);
                charsBudget = maxChars;
            }
            piece.appendCodePoint(point);
        }
        if (!piece.isEmpty()) {
            out.add(piece.toString());
        }
        return out;
    }
}
