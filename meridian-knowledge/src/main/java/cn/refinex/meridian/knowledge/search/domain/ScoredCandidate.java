package cn.refinex.meridian.knowledge.search.domain;

import java.util.Objects;

/**
 * 带分数的候选：融合后是 RRF 分，重排后是 rerank 模型的得分。分数只在同一次检索的结果之间可比。
 *
 * @param candidate 候选
 * @param score 分数，越大越相关
 */
public record ScoredCandidate(Candidate candidate, double score) {

    /**
     * 校验。
     *
     * @throws NullPointerException 缺少候选时
     */
    public ScoredCandidate {
        Objects.requireNonNull(candidate, "candidate");
    }
}
