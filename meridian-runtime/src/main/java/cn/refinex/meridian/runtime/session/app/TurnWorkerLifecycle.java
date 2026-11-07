package cn.refinex.meridian.runtime.session.app;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.runtime.dispatch.app.DispatchWakeups;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;

/**
 * Worker 的后台循环与节点优雅下线（架构设计「一轮执行的生命周期」「崩溃恢复」中的"节点优雅下线"）。
 *
 * <p>启动后订阅调度唤醒信号（{@code dispatch.wakeup} 通知与 1 秒轮询），每个信号尽量认领，直到没有可执行的轮次或本节点同时执行的
 * 轮次达到上限（{@code meridian.runtime.worker.max-concurrent-turns}）。
 *
 * <p>停止时按以下顺序：
 * <ol>
 *   <li>停止认领新轮次。
 *   <li>{@code AgentEngine.shutdown(60s)}：AgentScope 在下一个检查点以 {@code SYSTEM} 来源中断执行并保存状态，内核以
 *       {@code session.status_rescheduled}（{@code node_shutdown}）结束这些轮次，Worker 把它们置回队列。
 *   <li>仍未结束的执行：把轮次置回 {@code queued}（{@code recovery_attempt} 不变）并放弃执行，释放本节点的租约条目。
 * </ol>
 * 生命周期阶段取 {@link #PHASE}，在续租与回收任务（{@code DispatchConfiguration} 中阶段更小的 {@code PeriodicJob}）之前停止，
 * 等待执行结束期间仍在续租；Spring 停止全部生命周期组件之后才关闭数据源。AgentScope 自注册的 JVM 关闭钩子与本组件并存，
 * 两者的操作都是幂等的。
 *
 * <p>线程安全：执行集合是并发集合；启停由 Spring 在一个线程上调用。
 */
public final class TurnWorkerLifecycle implements SmartLifecycle {

    /** 生命周期阶段：最先停止，停止期间续租任务仍在运行。 */
    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE;

    /** 续租与回收任务的生命周期阶段：在 Worker 之后停止，等待执行结束期间仍在续租。 */
    public static final int LEASE_JOBS_PHASE = PHASE - 1024;

    private static final Logger log = LoggerFactory.getLogger(TurnWorkerLifecycle.class);

    private final TurnWorker worker;
    private final DispatchWakeups wakeups;
    private final WorkerProperties properties;
    private final Set<TurnExecution> running = ConcurrentHashMap.newKeySet();

    private volatile boolean accepting;
    private Disposable signals;

    /**
     * 创建生命周期组件。
     *
     * @param worker Worker
     * @param wakeups 调度唤醒信号
     * @param properties 执行配置，取并发上限与下线等待时长
     */
    public TurnWorkerLifecycle(TurnWorker worker, DispatchWakeups wakeups, WorkerProperties properties) {
        this.worker = worker;
        this.wakeups = wakeups;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (signals != null) {
            return;
        }
        accepting = true;
        signals = wakeups.signals()
                .concatMap(signal -> Blocking.run(this::drain))
                .subscribe(null, error -> log.error("Worker 的认领循环意外结束", error));
        log.info("Worker 已启动 maxConcurrentTurns={}", properties.maxConcurrentTurns());
    }

    @Override
    public synchronized void stop() {
        if (signals == null) {
            return;
        }
        accepting = false;
        signals.dispose();
        signals = null;
        shutdownExecutions();
    }

    @Override
    public synchronized boolean isRunning() {
        return signals != null;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /**
     * 本节点正在执行的轮次数。
     *
     * @return 执行数
     */
    public int runningCount() {
        return running.size();
    }

    /** 认领直到没有可执行的轮次或达到并发上限。在虚拟线程上执行。 */
    private void drain() {
        while (accepting && running.size() < properties.maxConcurrentTurns()) {
            Optional<TurnExecution> claimed = worker.claim();
            if (claimed.isEmpty()) {
                return;
            }
            launch(claimed.get());
        }
    }

    private void launch(TurnExecution execution) {
        running.add(execution);
        execution.run().doFinally(signal -> running.remove(execution)).subscribe();
    }

    private void shutdownExecutions() {
        Duration timeout = properties.shutdownTimeout();
        log.info("节点下线：停止认领，中断全部执行并等待保存状态 running={} timeout={}", running.size(), timeout);
        worker.engine().shutdown(timeout).block(timeout.plusSeconds(5));
        List<TurnExecution> remaining = List.copyOf(running);
        for (TurnExecution execution : remaining) {
            log.warn("节点下线时执行仍未结束，置回队列 turnId={}", execution.turn().turnId());
            execution.requeueForShutdown();
        }
        log.info("节点下线完成 requeued={}", remaining.size());
    }
}
