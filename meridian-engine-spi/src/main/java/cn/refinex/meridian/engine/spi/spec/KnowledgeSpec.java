package cn.refinex.meridian.engine.spi.spec;

import java.util.List;

/**
 * 智能体的知识库绑定与前置检索配置，见架构设计「平台中间件」中的 {@code KnowledgePrefetchMiddleware}
 * 与「平台工具」中的 {@code knowledge_search}。
 *
 * @param knowledgeBaseIds 绑定的知识库 ID（{@code kb_…}），{@code knowledge_search} 只能检索这些知识库；可为空列表
 * @param prefetch 是否在每轮开始时用最后一条用户消息检索并注入结果
 * @param prefetchTopK 前置检索注入的切片数，取值 [1, 50]；{@code prefetch} 为 false 时不使用
 */
public record KnowledgeSpec(List<String> knowledgeBaseIds, boolean prefetch, int prefetchTopK) {

    /** 前置检索默认注入的切片数。 */
    public static final int DEFAULT_PREFETCH_TOP_K = 5;

    /** 前置检索注入切片数的上限，避免一次注入挤占上下文。 */
    public static final int MAX_PREFETCH_TOP_K = 50;

    /**
     * 创建知识库配置。
     *
     * @throws IllegalArgumentException 开启前置检索但没有绑定知识库，或切片数超出取值范围时
     */
    public KnowledgeSpec {
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        if (prefetchTopK < 1 || prefetchTopK > MAX_PREFETCH_TOP_K) {
            throw new IllegalArgumentException("prefetchTopK 必须在 [1, " + MAX_PREFETCH_TOP_K + "] 内：" + prefetchTopK);
        }
        if (prefetch && knowledgeBaseIds.isEmpty()) {
            throw new IllegalArgumentException("开启前置检索时必须绑定至少一个知识库");
        }
    }

    /**
     * 不绑定任何知识库的配置。
     *
     * @return 空配置
     */
    public static KnowledgeSpec none() {
        return new KnowledgeSpec(List.of(), false, DEFAULT_PREFETCH_TOP_K);
    }
}
