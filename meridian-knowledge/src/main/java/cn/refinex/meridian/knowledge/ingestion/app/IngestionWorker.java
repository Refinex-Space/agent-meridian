package cn.refinex.meridian.knowledge.ingestion.app;

import cn.refinex.meridian.knowledge.ingestion.domain.ClaimedJob;
import cn.refinex.meridian.knowledge.ingestion.domain.IngestionFailure;
import cn.refinex.meridian.knowledge.ingestion.domain.RetrySchedule;
import cn.refinex.meridian.knowledge.ingestion.infra.system.IngestionQueueRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 写入作业的 Worker（ADR-0051）：每次轮询先收回租约过期的作业（崩溃或停顿的节点），再在空闲名额内逐个认领到期的作业，每个作业在
 * 自己的虚拟线程上执行 {@link IngestionPipeline}；定期为执行中的作业续租。轮询与续租由 {@code config} 中的后台任务驱动，上传与重新
 * 处理提交后调用 {@link #wakeUp()} 立即轮询一次。
 *
 * <p>线程安全：轮询串行（{@code synchronized}），执行中的作业记在并发映射里。
 */
public class IngestionWorker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);

    /** 一次收回的过期作业上限。 */
    static final int REAP_BATCH = 20;

    /** 关闭时等待执行中的作业的上限；没有结束的作业由租约过期后收回。 */
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final IngestionQueueRepository queue;
    private final IngestionPipeline pipeline;
    private final RetrySchedule retries;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String node;
    private final Duration lease;
    private final Semaphore permits;
    private final Map<String, ClaimedJob> running = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("meridian-ingestion-", 0).factory());

    /**
     * 创建 Worker。
     *
     * @param queue 作业队列
     * @param pipeline 流水线
     * @param retries 重试计划，收回过期作业时用
     * @param transactions 事务模板
     * @param clock 时钟
     * @param node 本节点标识
     * @param lease 租约时长
     * @param workers 同时执行的作业数
     */
    public IngestionWorker(
            IngestionQueueRepository queue,
            IngestionPipeline pipeline,
            RetrySchedule retries,
            TransactionTemplate transactions,
            Clock clock,
            String node,
            Duration lease,
            int workers) {
        this.queue = queue;
        this.pipeline = pipeline;
        this.retries = retries;
        this.transactions = transactions;
        this.clock = clock;
        this.node = node;
        this.lease = lease;
        this.permits = new Semaphore(workers);
    }

    /** 收回过期的作业，再认领到期的作业直到名额用完或队列为空。 */
    public synchronized void poll() {
        if (executor.isShutdown()) {
            return;
        }
        reapExpired();
        while (permits.tryAcquire()) {
            Optional<ClaimedJob> claimed;
            try {
                Instant now = clock.instant();
                claimed = transactions.execute(status -> queue.claim(node, now, now.plus(lease)));
            } catch (RuntimeException e) {
                permits.release();
                throw e;
            }
            if (claimed == null || claimed.isEmpty()) {
                permits.release();
                return;
            }
            ClaimedJob job = claimed.get();
            running.put(job.jobId(), job);
            log.info("认领写入作业 jobId={} documentId={} attempt={}", job.jobId(), job.documentId(), job.attempt());
            executor.submit(() -> {
                try {
                    pipeline.run(job);
                } finally {
                    running.remove(job.jobId());
                    permits.release();
                }
            });
        }
    }

    /** 立即在后台轮询一次（上传、重新处理提交之后）。 */
    public void wakeUp() {
        if (!executor.isShutdown()) {
            Thread.ofVirtual().name("meridian-ingestion-wakeup").start(() -> {
                try {
                    poll();
                } catch (RuntimeException e) {
                    log.warn("写入作业的即时轮询失败，等待下一次定期轮询", e);
                }
            });
        }
    }

    /** 为执行中的作业续租；续不上的（已被收回）只记日志，流水线在下一次写进度时放弃。 */
    public void renew() {
        Set<String> ids = new HashSet<>(running.keySet());
        if (ids.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        List<String> renewed = transactions.execute(status -> queue.renew(node, ids, now.plus(lease), now));
        if (renewed != null) {
            ids.removeAll(renewed);
        }
        if (!ids.isEmpty()) {
            log.warn("写入作业的租约已被收回 jobIds={}", ids);
        }
    }

    /**
     * 本节点正在执行的作业数。
     *
     * @return 作业数
     */
    public int runningCount() {
        return running.size();
    }

    private void reapExpired() {
        transactions.executeWithoutResult(status -> {
            Instant now = clock.instant();
            for (ClaimedJob job : queue.lockExpired(now, REAP_BATCH)) {
                if (running.containsKey(job.jobId())) {
                    // 本节点仍在执行（续租暂时失败）：不收回自己的作业
                    continue;
                }
                String message = "执行中的节点失去租约（第 " + job.attempt() + " 次尝试）";
                Optional<Instant> next = retries.next(job.attempt(), true, now);
                if (next.isPresent()) {
                    queue.retryLater(job, next.get(), IngestionFailure.LEASE_EXPIRED, message, now);
                } else {
                    queue.fail(job, IngestionFailure.LEASE_EXPIRED, message, true, now);
                }
                log.warn("收回租约过期的写入作业 jobId={} attempt={} retry={}", job.jobId(), job.attempt(), next.isPresent());
            }
        });
    }

    /** 停止认领，等待执行中的作业结束；超时的作业被中断，之后由租约过期收回。 */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("写入作业未在超时内结束，交给租约过期后收回 running={}", running.keySet());
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
