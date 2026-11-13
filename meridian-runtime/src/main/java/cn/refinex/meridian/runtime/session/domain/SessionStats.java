package cn.refinex.meridian.runtime.session.domain;

import java.time.Instant;
import java.util.List;

/**
 * 当前项目（或某个智能体）的会话运行概况（{@code GET /v1/session-stats}，ADR-0053）。
 *
 * @param window 时间窗
 * @param windowStart 时间窗的起点
 * @param generatedAt 生成时间
 * @param statsUpdatedAt 失败率汇总到的时间；还没有汇总过时为空
 * @param runningSessions 执行中（含被置回队列）的会话数
 * @param requiresActionSessions 等待回应的会话数
 * @param pendingActions 等待回应的待处理动作数
 * @param agents 每个智能体的统计，按智能体 ID 排序
 * @param recentErrors 时间窗内最近的失败轮次，新的在前
 */
public record SessionStats(
        StatsWindow window,
        Instant windowStart,
        Instant generatedAt,
        Instant statsUpdatedAt,
        long runningSessions,
        long requiresActionSessions,
        long pendingActions,
        List<AgentStats> agents,
        List<TurnError> recentErrors) {

    /** 复制列表。 */
    public SessionStats {
        agents = List.copyOf(agents);
        recentErrors = List.copyOf(recentErrors);
    }

    /**
     * 一个智能体的统计。
     *
     * @param agentId 智能体 ID
     * @param activeSessions 未终止、未删除的会话数
     * @param runningSessions 其中执行中的会话数
     * @param turns 时间窗内结束的轮次数（完成、中断、失败）
     * @param failedTurns 其中失败的轮次数
     */
    public record AgentStats(String agentId, long activeSessions, long runningSessions, long turns, long failedTurns) {

        /**
         * 失败率。
         *
         * @return 0 到 1；没有轮次时为空
         */
        public Double failureRate() {
            return turns == 0 ? null : (double) failedTurns / turns;
        }
    }

    /**
     * 一个失败的轮次。
     *
     * @param sessionId 会话 ID
     * @param threadId 线程 ID
     * @param turnId 轮次 ID
     * @param agentId 智能体 ID；可为空
     * @param code 错误码；找不到 {@code session.error} 事件时为空
     * @param message 错误信息；同上
     * @param occurredAt 轮次结束的时间
     */
    public record TurnError(
            String sessionId,
            String threadId,
            String turnId,
            String agentId,
            String code,
            String message,
            Instant occurredAt) {}
}
