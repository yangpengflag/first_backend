package com.mooc.backend.service.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）融合纯函数（change: ai-semantic-search，design.md D1）。
 *
 * <p>向量腿（余弦相似度）与关键词腿（LIKE 命中）的得分不可互相校准，故只用<b>排名</b>融合：
 * {@code score(d) = Σ 1/(K + rank_i(d))}，K=60（业界默认），每路各贡献一次。
 * 单路命中也参与融合——某路为空时退化为另一路的排名（SearchService 层的降级语义由此自然获得）。
 * 同一候选在单路内重复出现（如同一实体命中多个 chunk）只取最优排名计分一次。
 * 排序：分数降序，同分按 {@code (type, key)} 字典序稳定输出（保证结果确定）。
 */
public final class RrfFusion {

    public static final int K = 60;

    /** 融合候选的唯一键：实体类型 + 实体键（city/spot 为 slug，post 为 uuid）。 */
    public record Candidate(String type, String key) {
    }

    public record Scored(Candidate candidate, double score) {
    }

    private RrfFusion() {
    }

    /**
     * @param rankedA 第一路候选（已按相关性降序排列，rank 从 1 计）
     * @param rankedB 第二路候选（同上）
     */
    public static List<Scored> fuse(List<Candidate> rankedA, List<Candidate> rankedB) {
        Map<Candidate, Double> scores = new LinkedHashMap<>();
        accumulate(scores, rankedA);
        accumulate(scores, rankedB);
        List<Scored> out = new ArrayList<>(scores.size());
        scores.forEach((c, s) -> out.add(new Scored(c, s)));
        out.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(s -> s.candidate().type())
                .thenComparing(s -> s.candidate().key()));
        return out;
    }

    /** 单路计分：先按出现顺序去重（首次出现即最优排名），再按 1/(K+rank) 累加。 */
    private static void accumulate(Map<Candidate, Double> scores, List<Candidate> ranked) {
        Map<Candidate, Integer> bestRank = new LinkedHashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            bestRank.putIfAbsent(ranked.get(i), i + 1);
        }
        bestRank.forEach((c, rank) -> scores.merge(c, 1.0 / (K + rank), Double::sum));
    }
}
