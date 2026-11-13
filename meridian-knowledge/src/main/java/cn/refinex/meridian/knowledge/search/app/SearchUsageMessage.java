package cn.refinex.meridian.knowledge.search.app;

import java.time.Instant;
import java.util.List;

/**
 * 知识检索次数的用量消息（outbox 主题 {@code usage}，T19，登记事项 D70），由 control-plane 的计费消费者入账，只进账本、不占预算。
 * 格式以 {@code meridian-test-support} 的样本 {@code usage/knowledge_search_calls.json} 为准。
 *
 * @param kind 种类，固定为 {@value #KIND}
 * @param occurredAt 检索完成的时间
 * @param tenantId 租户 ID
 * @param projectId 项目 ID
 * @param apiKeyId 发起检索的 API Key；可为空
 * @param sessionId 会话 ID（智能体检索时）；可为空
 * @param turnId 轮次 ID（智能体检索时）；可为空
 * @param source 入口：{@code rest}、{@code mcp}、{@code agent}
 * @param knowledgeBaseIds 检索的知识库
 * @param count 次数，固定为 1
 */
public record SearchUsageMessage(
        String kind,
        Instant occurredAt,
        String tenantId,
        String projectId,
        String apiKeyId,
        String sessionId,
        String turnId,
        String source,
        List<String> knowledgeBaseIds,
        int count) {

    /** 用量种类。 */
    public static final String KIND = "knowledge_search_calls";
}
