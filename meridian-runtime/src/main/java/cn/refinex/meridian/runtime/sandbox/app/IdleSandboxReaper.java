package cn.refinex.meridian.runtime.sandbox.app;

import cn.refinex.meridian.runtime.sandbox.app.SandboxService.ReclaimOutcome;
import cn.refinex.meridian.runtime.sandbox.infra.system.IdleSandboxRepository;
import cn.refinex.meridian.runtime.sandbox.infra.system.IdleSandboxRepository.IdleSession;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 空闲回收器（架构设计「会话沙箱」"空闲回收"）：找出沙箱空闲超过 {@link SandboxProperties#idleTimeout()} 的会话，逐个交给
 * {@link SandboxService#reclaim} 在会话行锁内重新判定并回收。由 {@code PeriodicJob} 按 {@link SandboxProperties#reapInterval()}
 * 调用；多个节点同时扫描时，行锁 {@code SKIP LOCKED} 保证一个会话只被一个节点回收。
 */
public class IdleSandboxReaper {

    private static final Logger log = LoggerFactory.getLogger(IdleSandboxReaper.class);

    private final IdleSandboxRepository idle;
    private final SandboxService sandboxes;
    private final SandboxProperties properties;
    private final Clock clock;

    /**
     * 创建回收器。
     *
     * @param idle 空闲扫描
     * @param sandboxes 沙箱服务
     * @param properties 沙箱配置
     * @param clock 时钟
     */
    public IdleSandboxReaper(
            IdleSandboxRepository idle, SandboxService sandboxes, SandboxProperties properties, Clock clock) {
        this.idle = idle;
        this.sandboxes = sandboxes;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 扫描一次。单个会话失败只记日志，不影响其他会话。
     *
     * @return 各判定结果的会话数
     */
    public Map<ReclaimOutcome, Integer> reapOnce() {
        Instant cutoff = clock.instant().minus(properties.idleTimeout());
        Map<ReclaimOutcome, Integer> outcomes = new EnumMap<>(ReclaimOutcome.class);
        for (IdleSession session : idle.findIdle(cutoff, properties.reapBatchSize())) {
            try {
                ReclaimOutcome outcome =
                        sandboxes.reclaim(session.tenantId(), session.projectId(), session.sessionId(), cutoff);
                outcomes.merge(outcome, 1, Integer::sum);
            } catch (RuntimeException e) {
                log.warn("回收会话沙箱失败，下次扫描重试 sessionId={}", session.sessionId(), e);
            }
        }
        return outcomes;
    }
}
