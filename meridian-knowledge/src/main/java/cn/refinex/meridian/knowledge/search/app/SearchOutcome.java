package cn.refinex.meridian.knowledge.search.app;

import cn.refinex.meridian.knowledge.search.domain.ScoredCandidate;
import java.util.List;

/**
 * 一次检索的结果。
 *
 * @param query 实际用于检索的查询：改写成功时是改写后的查询，否则是原查询
 * @param hits 命中的切片，按分数降序，至多 {@code topK} 个
 * @param reranked 是否经过了 rerank（决定 {@code score} 的含义）
 */
public record SearchOutcome(String query, List<ScoredCandidate> hits, boolean reranked) {

    /** 复制结果列表。 */
    public SearchOutcome {
        hits = List.copyOf(hits);
    }
}
