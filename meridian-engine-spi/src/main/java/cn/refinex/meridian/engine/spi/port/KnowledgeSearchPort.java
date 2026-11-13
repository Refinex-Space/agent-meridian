package cn.refinex.meridian.engine.spi.port;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * 知识库检索，供 {@code knowledge_search} 工具与 {@code KnowledgePrefetchMiddleware} 使用，由 runtime 的
 * {@code tooling} 实现（经 HTTP 调用 knowledge 服务），见架构设计「端口」「检索流程」。
 *
 * <p>租户、项目与会话从 Reactor Context 中的 {@code PlatformContext} 取；终端用户的 ACL 标签由实现从会话读取，调用方传不进来。
 * knowledge 不可用时以错误结束，工具把它作为错误结果返回给智能体，前置检索跳过注入。
 */
public interface KnowledgeSearchPort {

    /**
     * 混合检索。
     *
     * @param query 检索请求
     * @return 按相关度降序的切片；没有命中时为空列表
     */
    Mono<List<Hit>> search(Query query);

    /**
     * 检索请求。
     *
     * @param knowledgeBaseIds 要检索的知识库 ID（{@code kb_…}），必须是智能体绑定的知识库，至少一个
     * @param query 查询文本；必填
     * @param topK 返回的切片数，取值 [1, 50]
     * @param rewriteModel 查询改写用的对话模型 ID（智能体配置的 {@code knowledge.query_rewrite_model}）；为空表示不改写
     */
    record Query(List<String> knowledgeBaseIds, String query, int topK, String rewriteModel) {

        /**
         * 创建检索请求。
         *
         * @throws NullPointerException 缺少查询文本时
         * @throws IllegalArgumentException 没有知识库或切片数超出范围时
         */
        public Query {
            Objects.requireNonNull(query, "query");
            knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
            if (knowledgeBaseIds.isEmpty()) {
                throw new IllegalArgumentException("至少要检索一个知识库");
            }
            if (topK < 1 || topK > 50) {
                throw new IllegalArgumentException("topK 必须在 [1, 50] 内：" + topK);
            }
        }

        /**
         * 创建不改写查询的检索请求。
         *
         * @param knowledgeBaseIds 要检索的知识库 ID
         * @param query 查询文本
         * @param topK 返回的切片数
         */
        public Query(List<String> knowledgeBaseIds, String query, int topK) {
            this(knowledgeBaseIds, query, topK, null);
        }
    }

    /**
     * 一个命中的切片。
     *
     * @param knowledgeBaseId 知识库 ID；必填
     * @param documentId 文档 ID（{@code doc_…}）；必填
     * @param documentName 文档名，用于引用展示；必填
     * @param content 切片文本；必填
     * @param score 相关度，越大越相关
     * @param sourceLocator 原文定位：{@code chunk_id}、{@code page_start}、{@code page_end}（没有分页时不出现）、{@code heading_path}
     *     （字符串列表）；可为空映射
     */
    record Hit(
            String knowledgeBaseId,
            String documentId,
            String documentName,
            String content,
            double score,
            Map<String, Object> sourceLocator) {

        /**
         * 创建命中切片。
         *
         * @throws NullPointerException 缺少必填字段时
         */
        public Hit {
            Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId");
            Objects.requireNonNull(documentId, "documentId");
            Objects.requireNonNull(documentName, "documentName");
            Objects.requireNonNull(content, "content");
            sourceLocator = sourceLocator == null ? Map.of() : Map.copyOf(sourceLocator);
        }
    }
}
