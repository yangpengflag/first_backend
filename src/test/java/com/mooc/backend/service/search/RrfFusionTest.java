package com.mooc.backend.service.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RRF（Reciprocal Rank Fusion, k=60）融合纯函数测试（change: ai-semantic-search，tasks 3.1 / design.md D1）。
 */
class RrfFusionTest {

    private static RrfFusion.Candidate c(String type, String key) {
        return new RrfFusion.Candidate(type, key);
    }

    @Test
    void dualHitRanksAboveSingleHit() {
        // spot:west-lake 两路都命中（向量 rank1 + 关键词 rank1）；city:chengdu 仅向量 rank2；
        // spot:lingyin 仅关键词 rank2 → 双命中者第一
        List<RrfFusion.Scored> fused = RrfFusion.fuse(
                List.of(c("spot", "west-lake"), c("city", "chengdu")),
                List.of(c("spot", "west-lake"), c("spot", "lingyin")));

        assertThat(fused.get(0).candidate()).isEqualTo(c("spot", "west-lake"));
        assertThat(fused.get(0).score()).isGreaterThan(fused.get(1).score());
        // 1/(60+2) 两个单路 rank2 同分
        assertThat(fused.get(1).score()).isEqualTo(fused.get(2).score());
    }

    @Test
    void emptyVectorLegYieldsPureKeywordRanking() {
        List<RrfFusion.Scored> fused = RrfFusion.fuse(
                List.of(),
                List.of(c("spot", "a"), c("spot", "b"), c("spot", "c")));

        assertThat(fused).extracting(RrfFusion.Scored::candidate)
                .containsExactly(c("spot", "a"), c("spot", "b"), c("spot", "c"));
        assertThat(fused.get(0).score()).isGreaterThan(fused.get(1).score());
    }

    @Test
    void emptyKeywordLegYieldsPureVectorRanking() {
        List<RrfFusion.Scored> fused = RrfFusion.fuse(
                List.of(c("city", "x"), c("city", "y")),
                List.of());

        assertThat(fused).extracting(RrfFusion.Scored::candidate)
                .containsExactly(c("city", "x"), c("city", "y"));
    }

    @Test
    void duplicateChunksKeepBestRankOnly() {
        // 同一实体多个 chunk：向量腿 rank1 与 rank3 均为 spot:west-lake → 只取 rank1 计分一次
        List<RrfFusion.Scored> fused = RrfFusion.fuse(
                List.of(c("spot", "west-lake"), c("spot", "other"), c("spot", "west-lake")),
                List.of());

        assertThat(fused).hasSize(2);
        assertThat(fused.get(0).candidate()).isEqualTo(c("spot", "west-lake"));
        assertThat(fused.get(0).score()).isEqualTo(1.0 / (RrfFusion.K + 1));
    }

    @Test
    void equalScoresBreakTieByTypeThenKeyDeterministically() {
        // 两个实体各自单路 rank1 同分（不同类型）→ 按 type 再 key 字典序稳定输出
        List<RrfFusion.Scored> fused = RrfFusion.fuse(
                List.of(c("spot", "z")),
                List.of(c("city", "a")));

        assertThat(fused).extracting(RrfFusion.Scored::candidate)
                .containsExactly(c("city", "a"), c("spot", "z"));
    }

    @Test
    void bothLegsEmptyYieldsEmpty() {
        assertThat(RrfFusion.fuse(List.of(), List.of())).isEmpty();
    }
}
