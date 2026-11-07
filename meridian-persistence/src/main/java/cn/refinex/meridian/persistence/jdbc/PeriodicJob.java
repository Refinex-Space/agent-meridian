package cn.refinex.meridian.persistence.jdbc;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 按固定间隔执行的后台任务，随 Spring 容器启停：outbox 投递、过期数据清理等。
 *
 * <p>任务在一个专用的虚拟线程上串行执行：上一次没结束，下一次不会开始（固定延迟而不是固定频率）。
 * 任务抛出的异常记录后继续调度，不会让调度停止。多实例部署时每个实例都会运行，任务自身必须能并发执行
 * （例如用 {@code SKIP LOCKED} 或带 {@code LIMIT} 的删除）。
 *
 * <p>停止时等待正在执行的一次结束。Spring 先停止所有 Lifecycle 组件，再销毁 Bean（包括关闭数据源），
 * 所以任务不会在数据源关闭后还在执行。
 */
public final class PeriodicJob implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PeriodicJob.class);

    /** 停止时等待当前一次执行结束的上限。 */
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final String name;
    private final Duration interval;
    private final Runnable task;
    private final int phase;
    private ScheduledExecutorService executor;

    /**
     * 创建后台任务。
     *
     * @param name 任务名，用于线程名与日志
     * @param interval 两次执行之间的间隔
     * @param task 任务
     */
    public PeriodicJob(String name, Duration interval, Runnable task) {
        this(name, interval, task, DEFAULT_PHASE);
    }

    /**
     * 创建指定生命周期阶段的后台任务。阶段小的先启动、后停止：别的组件停止时仍依赖本任务运行（例如节点下线等待执行结束时
     * 仍要续租），就给本任务一个更小的阶段。
     *
     * @param name 任务名，用于线程名与日志
     * @param interval 两次执行之间的间隔
     * @param task 任务
     * @param phase 生命周期阶段，见 {@link SmartLifecycle#getPhase()}
     */
    public PeriodicJob(String name, Duration interval, Runnable task, int phase) {
        this.name = name;
        this.interval = interval;
        this.task = task;
        this.phase = phase;
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("meridian-job-" + name).factory());
        executor.scheduleWithFixedDelay(
                this::runSafely, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("后台任务未在超时内结束 job={}", name);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        executor = null;
    }

    @Override
    public int getPhase() {
        return phase;
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }

    private void runSafely() {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.error("后台任务执行失败 job={}", name, e);
        }
    }
}
