package cn.refinex.meridian.knowledge.metering.app;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 知识库存储量的用量消息（outbox 主题 {@code usage}，ADR-0051），由 control-plane 的计费消费者入账。格式以 {@code meridian-test-support}
 * 的样本 {@code usage/knowledge_storage_gb_days.json} 为准。
 *
 * @param kind 种类，固定为 {@value #KIND}
 * @param occurredAt 计量日结束的时刻（次日 00:00 UTC），决定账本的分区
 * @param tenantId 租户 ID
 * @param projectId 项目 ID
 * @param knowledgeBaseId 知识库 ID
 * @param day 计量的日期（UTC，{@code yyyy-MM-dd}）
 * @param fileBytes 原文件字节数之和
 * @param indexBytes 切片正文与向量字节数之和
 * @param gbDays 数量：总字节数除以 10^9，即这一天的 GB 天
 */
public record StorageUsageMessage(
        String kind,
        Instant occurredAt,
        String tenantId,
        String projectId,
        String knowledgeBaseId,
        String day,
        long fileBytes,
        long indexBytes,
        BigDecimal gbDays) {

    /** 用量种类。 */
    public static final String KIND = "knowledge_storage_gb_days";

    /**
     * 组装一天的用量。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param knowledgeBaseId 知识库 ID
     * @param day 日期（UTC）
     * @param fileBytes 原文件字节数
     * @param indexBytes 切片字节数
     * @return 消息
     */
    public static StorageUsageMessage of(
            String tenantId, String projectId, String knowledgeBaseId, LocalDate day, long fileBytes, long indexBytes) {
        BigDecimal gbDays =
                BigDecimal.valueOf(fileBytes + indexBytes).movePointLeft(9).setScale(9, RoundingMode.UNNECESSARY);
        return new StorageUsageMessage(
                KIND,
                day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC),
                tenantId,
                projectId,
                knowledgeBaseId,
                day.toString(),
                fileBytes,
                indexBytes,
                gbDays);
    }
}
