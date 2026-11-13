package cn.refinex.meridian.knowledge.search.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF 融合（架构设计「检索流程」第 3 步）：每路排名第 r（从 1 起）的候选得 {@code 1 / (k + r)}，各路得分相加后降序。
 *
 * <p>常数 k 取 60，出自 Cormack、Clarke、Büttcher 2009 年的论文 "Reciprocal Rank Fusion outperforms Condorcet and individual
 * Rank Learning Methods"（SIGIR '09），也是 Elasticsearch、OpenSearch 的 RRF 默认值；它压低排名靠前的单路结果的优势，让两路都靠前的
 * 候选胜出。得分相同时按首次出现的顺序（先向量、后全文）稳定排列。
 *
 * <p>线程安全：不可变。
 */
public final class ReciprocalRankFusion {

    /** 常数 k 的常用值（见类注释）。 */
    public static final int DEFAULT_K = 60;

    private final int k;

    /**
     * 创建融合器。
     *
     * @param k 常数，必须为正
     * @throws IllegalArgumentException k 不为正时
     */
    public ReciprocalRankFusion(int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("RRF 的常数 k 必须为正：" + k);
        }
        this.k = k;
    }

    /**
     * 融合多路排名。同一切片在一路中重复出现时只计首次。
     *
     * @param rankings 每路按相关度降序的候选
     * @return 融合后按得分降序的候选
     */
    public List<ScoredCandidate> fuse(List<List<Candidate>> rankings) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<Candidate> ranking : rankings) {
            Map<String, Boolean> seen = new LinkedHashMap<>();
            int rank = 0;
            for (Candidate candidate : ranking) {
                if (seen.putIfAbsent(candidate.chunkId(), Boolean.TRUE) != null) {
                    continue;
                }
                rank++;
                candidates.putIfAbsent(candidate.chunkId(), candidate);
                scores.merge(candidate.chunkId(), 1.0 / (k + rank), Double::sum);
            }
        }
        List<ScoredCandidate> fused = new ArrayList<>(candidates.size());
        candidates.forEach((id, candidate) -> fused.add(new ScoredCandidate(candidate, scores.get(id))));
        // List.sort 是稳定排序：得分相同的保持首次出现的顺序
        fused.sort(Comparator.comparingDouble(ScoredCandidate::score).reversed());
        return fused;
    }
}
