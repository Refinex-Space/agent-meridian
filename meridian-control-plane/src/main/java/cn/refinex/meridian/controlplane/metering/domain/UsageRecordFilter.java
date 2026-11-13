package cn.refinex.meridian.controlplane.metering.domain;

/**
 * 用量明细的筛选条件（D68，ADR-0053）：条件之间是"且"，为空的条件不过滤。
 *
 * @param projectId 项目；可为空
 * @param kind 计量种类；可为空
 * @param modelId 模型；可为空
 * @param agentId 智能体；可为空
 * @param sessionId 会话；可为空
 * @param apiKeyId API Key；可为空
 */
public record UsageRecordFilter(
        String projectId, UsageKind kind, String modelId, String agentId, String sessionId, String apiKeyId) {}
