package cn.refinex.meridian.runtime.session.app;

import cn.refinex.meridian.engine.spi.engine.RecoveryOrigin;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.runtime.dispatch.domain.ClaimedTurn;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * 轮次执行的指标（架构设计「可观测性」）。Micrometer 名用点分隔，导出到 Prometheus 时转为下划线并按类型加后缀：
 *
 * <ul>
 *   <li>{@code meridian.turns}（counter，{@code status}、{@code stop_reason}）→ {@code meridian_turns_total}：每次执行的结束，
 *       {@code status} 为轮次状态（{@code completed}、{@code interrupted}、{@code failed}、{@code awaiting_action}）或
 *       {@code queued}（置回队列）；没有停止原因时 {@code stop_reason} 为 {@code none}。
 *   <li>{@code meridian.turn.duration}（timer，{@code agent_version}）→ {@code meridian_turn_duration_seconds}：一次执行从认领到
 *       结束的时长。
 *   <li>{@code meridian.turn.queue.wait}（timer，{@code tenant}）→ {@code meridian_turn_queue_wait_seconds}：轮次从入队到第一次被
 *       认领的时长；恢复认领不计入。
 *   <li>{@code meridian.turn.recoveries}（counter，{@code mode} 为 {@code replay} 或 {@code recovery_only}）→
 *       {@code meridian_turn_recoveries_total}：继续执行的恢复认领。
 *   <li>{@code meridian.lease.lost}（counter）→ {@code meridian_lease_lost_total}：执行因失去租约而放弃。
 * </ul>
 * 队列深度 {@code meridian.dispatch.queue.depth} 由 {@code dispatch.app.QueueDepthGauge} 维护。
 *
 * <p>线程安全：Micrometer 的计量器线程安全。
 */
@Component
public class TurnMetrics {

    private final MeterRegistry registry;
    private final Counter leaseLost;

    /**
     * 创建指标。
     *
     * @param registry 指标注册表
     */
    public TurnMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.leaseLost = Counter.builder("meridian.lease.lost")
                .description("执行因失去租约而放弃的次数")
                .register(registry);
    }

    /**
     * 记录一次认领：第一次认领时记录排队时长。
     *
     * @param turn 认领到的轮次
     */
    public void claimed(ClaimedTurn turn) {
        if (!turn.isRecovery()) {
            Timer.builder("meridian.turn.queue.wait")
                    .description("轮次从入队到第一次被认领的时长")
                    .tag("tenant", turn.tenantId())
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(Duration.between(turn.createdAt(), turn.claimedAt()));
        }
    }

    /**
     * 记录一次继续执行的恢复认领。
     *
     * @param mode 恢复方式
     */
    public void recovered(RecoveryOrigin.Mode mode) {
        Counter.builder("meridian.turn.recoveries")
                .description("继续执行的恢复认领次数")
                .tag("mode", mode == RecoveryOrigin.Mode.REPLAY_INPUT ? "replay" : "recovery_only")
                .register(registry)
                .increment();
    }

    /**
     * 记录一次执行的结束。
     *
     * @param status 轮次状态，置回队列时为 {@code queued}
     * @param stopReason 停止原因；可为空
     */
    public void ended(String status, StopReason stopReason) {
        Counter.builder("meridian.turns")
                .description("执行结束的次数，按轮次状态与停止原因")
                .tag("status", status)
                .tag("stop_reason", stopReason == null ? "none" : stopReason.value())
                .register(registry)
                .increment();
    }

    /**
     * 记录一次执行的时长。
     *
     * @param agentVersionId 智能体版本 ID
     * @param duration 从认领到结束的时长
     */
    public void executed(String agentVersionId, Duration duration) {
        Timer.builder("meridian.turn.duration")
                .description("一次执行从认领到结束的时长")
                .tag("agent_version", agentVersionId)
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    /** 记录一次因失去租约而放弃的执行。 */
    public void leaseLost() {
        leaseLost.increment();
    }
}
