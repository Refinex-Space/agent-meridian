package cn.refinex.meridian.runtime.session.app;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 轮次执行的配置，前缀 {@code meridian.runtime.worker}。
 *
 * @param maxConcurrentTurns 本节点同时执行的轮次上限，默认 300（单个 runtime Pod 的目标并发轮次数，ADR-0027 第 7 条的依据）；
 *     达到上限时不再认领，由其他节点认领
 * @param shutdownTimeout 节点下线时等待执行结束的上限，默认 60 秒（架构设计「崩溃恢复」中的
 *     {@code awaitTermination(Duration.ofSeconds(60))}）
 * @param pendingActionTimeout 待处理动作的默认截止时长，默认 30 分钟（架构设计「待处理动作」）
 * @param batchCheckpoint 每批工具执行完成后是否保存状态，默认开启（P3，ADR-0028）；关闭时强杀只能整轮恢复
 * @param maxSubagentsPerTurn 每轮拉起子智能体的上限，默认 8（架构设计「子智能体」，由 {@code PolicyMiddleware} 执行）
 */
@ConfigurationProperties("meridian.runtime.worker")
public record WorkerProperties(
        @DefaultValue("300") int maxConcurrentTurns,
        @DefaultValue("60s") Duration shutdownTimeout,
        @DefaultValue("30m") Duration pendingActionTimeout,
        @DefaultValue("true") boolean batchCheckpoint,
        @DefaultValue("8") int maxSubagentsPerTurn) {

    /**
     * 校验取值。
     *
     * @throws IllegalArgumentException 上限不为正、时长不为正时
     */
    public WorkerProperties {
        if (maxSubagentsPerTurn < 1) {
            throw new IllegalArgumentException("max-subagents-per-turn 必须为正：" + maxSubagentsPerTurn);
        }
        if (maxConcurrentTurns < 1) {
            throw new IllegalArgumentException("max-concurrent-turns 必须为正：" + maxConcurrentTurns);
        }
        if (shutdownTimeout == null || shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
            throw new IllegalArgumentException("shutdown-timeout 必须为正：" + shutdownTimeout);
        }
        if (pendingActionTimeout == null || pendingActionTimeout.isNegative() || pendingActionTimeout.isZero()) {
            throw new IllegalArgumentException("pending-action-timeout 必须为正：" + pendingActionTimeout);
        }
    }

    /**
     * 全部取默认值的配置，供测试与手工装配使用。
     *
     * @return 配置
     */
    public static WorkerProperties defaults() {
        return new WorkerProperties(300, Duration.ofSeconds(60), Duration.ofMinutes(30), true, 8);
    }
}
