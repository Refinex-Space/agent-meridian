package cn.refinex.meridian.controlplane.metering.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 报表中的一组合计。没有按某个维度聚合时，该维度为空。
 *
 * @param day UTC 日期
 * @param projectId 项目 ID
 * @param modelId 模型 ID
 * @param agentId 智能体 ID
 * @param apiKeyId API Key ID
 * @param kind 计量种类
 * @param currency 币种；没有计价时为空
 * @param records 记录数
 * @param quantity 数量合计
 * @param inputTokens 输入 token
 * @param outputTokens 输出 token
 * @param cacheReadTokens 缓存读 token
 * @param cacheWriteTokens 缓存写 token
 * @param reasoningTokens 推理 token
 * @param cost 成本合计；没有计价时为空
 */
public record UsageReportRow(
        LocalDate day,
        String projectId,
        String modelId,
        String agentId,
        String apiKeyId,
        UsageKind kind,
        String currency,
        long records,
        BigDecimal quantity,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        long reasoningTokens,
        BigDecimal cost) {}
