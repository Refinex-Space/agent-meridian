package cn.refinex.meridian.knowledge.search.app;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 一次检索的入参。租户与项目取自当前 {@code PlatformContext}。
 *
 * @param knowledgeBaseIds 要检索的知识库，至少一个，去重并保留顺序
 * @param query 查询文本，去掉首尾空白后不能为空
 * @param topK 返回的切片数，[1, {@value #MAX_TOP_K}]
 * @param callerTags 调用方（终端用户）的访问标签；可为空列表，表示只能看到不限标签的切片
 * @param rewriteModel 查询改写的对话模型；为空表示不改写
 * @param attribution 用量归属
 */
public record SearchRequest(
        List<String> knowledgeBaseIds,
        String query,
        int topK,
        List<String> callerTags,
        String rewriteModel,
        Attribution attribution) {

    /** 切片数的上限，与接口的 {@code top_k} 上限一致。 */
    public static final int MAX_TOP_K = 50;

    /**
     * 校验并规整。
     *
     * @throws IllegalArgumentException 没有知识库、查询为空或切片数超出范围时
     */
    public SearchRequest {
        knowledgeBaseIds = List.copyOf(new LinkedHashSet<>(knowledgeBaseIds));
        if (knowledgeBaseIds.isEmpty()) {
            throw new IllegalArgumentException("至少要检索一个知识库");
        }
        query = Objects.requireNonNull(query, "query").strip();
        if (query.isEmpty()) {
            throw new IllegalArgumentException("查询不能为空");
        }
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new IllegalArgumentException("topK 必须在 [1, " + MAX_TOP_K + "] 内：" + topK);
        }
        callerTags = callerTags == null ? List.of() : List.copyOf(new LinkedHashSet<>(callerTags));
        rewriteModel = rewriteModel == null || rewriteModel.isBlank() ? null : rewriteModel;
        Objects.requireNonNull(attribution, "attribution");
    }

    /**
     * 用量归属：哪个入口、代表哪个 API Key 与会话发起。
     *
     * @param source 入口：{@code rest}、{@code mcp}、{@code agent}
     * @param apiKeyId API Key ID；可为空
     * @param sessionId 会话 ID；可为空
     * @param turnId 轮次 ID；可为空
     */
    public record Attribution(String source, String apiKeyId, String sessionId, String turnId) {

        /** 公开 API。 */
        public static final String REST = "rest";

        /** MCP Server。 */
        public static final String MCP = "mcp";

        /** 智能体（runtime 的 {@code knowledge_search} 与前置检索）。 */
        public static final String AGENT = "agent";

        /**
         * 校验。
         *
         * @throws NullPointerException 缺少入口时
         */
        public Attribution {
            Objects.requireNonNull(source, "source");
        }
    }
}
