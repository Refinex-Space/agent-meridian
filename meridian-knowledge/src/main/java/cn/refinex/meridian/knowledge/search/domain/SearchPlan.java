package cn.refinex.meridian.knowledge.search.domain;

/**
 * 检索方式：召回哪几路、是否重排。对外的三个入口都用 {@link #STANDARD}；其余组合只给检索评测对比（T19-A03），见
 * {@code docs/engineering/retrieval-evaluation.md}。
 *
 * @param vector 是否做向量召回
 * @param fullText 是否做全文召回
 * @param rerank 是否用知识库的 rerank 模型重排（知识库没有设置 rerank 模型时不重排）
 */
public record SearchPlan(boolean vector, boolean fullText, boolean rerank) {

    /** 架构设计「检索流程」：向量与全文并行召回、RRF 融合、rerank。 */
    public static final SearchPlan STANDARD = new SearchPlan(true, true, true);

    /** 只有向量召回。 */
    public static final SearchPlan VECTOR_ONLY = new SearchPlan(true, false, false);

    /** 只有全文召回。 */
    public static final SearchPlan FULL_TEXT_ONLY = new SearchPlan(false, true, false);

    /** 两路融合、不重排。 */
    public static final SearchPlan HYBRID = new SearchPlan(true, true, false);

    /**
     * 校验。
     *
     * @throws IllegalArgumentException 两路都不召回时
     */
    public SearchPlan {
        if (!vector && !fullText) {
            throw new IllegalArgumentException("至少要有一路召回");
        }
    }

    /**
     * 评测记录中的名字。
     *
     * @return 名字
     */
    public String label() {
        if (vector && fullText) {
            return rerank ? "hybrid+rerank" : "hybrid";
        }
        return (vector ? "vector" : "full_text") + (rerank ? "+rerank" : "");
    }
}
