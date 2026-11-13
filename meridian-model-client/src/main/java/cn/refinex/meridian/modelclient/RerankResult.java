package cn.refinex.meridian.modelclient;

import java.util.List;

/**
 * 重排的结果。
 *
 * @param scores 得分，按相关度从高到低
 * @param promptTokens runtime 报告的输入 token；只用于观测，记账在 runtime
 */
public record RerankResult(List<Score> scores, long promptTokens) {

    /** 复制得分列表。 */
    public RerankResult {
        scores = List.copyOf(scores);
    }

    /**
     * 一条候选的得分。
     *
     * @param index 候选在请求中的序号
     * @param relevance 相关度，越大越相关，取供应商的值
     */
    public record Score(int index, double relevance) {}
}
