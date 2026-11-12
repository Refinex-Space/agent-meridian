package cn.refinex.meridian.modelclient;

import java.util.List;

/**
 * 一批 Embedding 的结果。
 *
 * @param vectors 向量，与输入一一对应、同序
 * @param promptTokens runtime 报告的输入 token（供应商报告或估算）；只用于观测，记账在 runtime
 */
public record EmbeddingResult(List<float[]> vectors, long promptTokens) {

    /** 复制向量列表。 */
    public EmbeddingResult {
        vectors = List.copyOf(vectors);
    }
}
