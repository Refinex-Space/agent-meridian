package cn.refinex.meridian.runtime.dispatch.domain;

import java.time.Instant;

/**
 * 被本节点认领的轮次，即认领事务第 3 步更新后的行（架构设计「一轮执行的生命周期」中的「认领」）。
 *
 * @param turnId 轮次 ID
 * @param tenantId 租户 ID
 * @param projectId 项目 ID
 * @param sessionId 会话 ID
 * @param threadId 线程 ID
 * @param kind 轮次类型：{@code messages}、{@code confirmations}、{@code custom_tool_results}、{@code recovery}
 * @param epoch 本次执行的租约 epoch，所有受租约约束的写入都以它为条件
 * @param claimCount 包括本次在内被认领的次数
 * @param recoveryAttempt 恢复次数；大于 0 表示这是恢复认领，失败那次执行的 epoch 为 {@code epoch - 1}
 * @param interruptRequestedAt 认领前已收到的中断请求时间；可为空，表示没有
 * @param createdAt 入队时间
 * @param claimedAt 本次认领的时间
 * @param leaseExpiresAt 租约到期时间
 */
public record ClaimedTurn(
        String turnId,
        String tenantId,
        String projectId,
        String sessionId,
        String threadId,
        String kind,
        long epoch,
        int claimCount,
        int recoveryAttempt,
        Instant interruptRequestedAt,
        Instant createdAt,
        Instant claimedAt,
        Instant leaseExpiresAt) {

    /**
     * 是否为恢复认领：轮次曾因租约过期或可重试错误被置回队列。
     *
     * @return {@code recoveryAttempt} 大于 0 时为 true
     */
    public boolean isRecovery() {
        return recoveryAttempt > 0;
    }
}
