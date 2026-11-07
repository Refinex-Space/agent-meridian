package cn.refinex.meridian.runtime.dispatch.app;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.runtime.dispatch.domain.NewTurn;
import cn.refinex.meridian.runtime.dispatch.domain.TurnOutcome;
import cn.refinex.meridian.runtime.stream.infra.EngineJson;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.support.TestNode;
import cn.refinex.meridian.runtime.support.TestSessions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * T05-A02：同一线程并发插入多条 {@code queued} 轮次时只保留一条，计数行、轮次与输入同事务写入，不产生无法认领的"毒丸"。
 */
class TurnEnqueueIT {

    private static final int CONCURRENT_INSERTS = 8;

    private final MutableClock clock = MutableClock.at(Instant.parse("2100-01-01T00:00:00Z"));
    private TestNode node;

    @BeforeEach
    void setUp() {
        RuntimeTestDatabase.truncate();
        node = TestNode.create("node-a", clock);
    }

    @RepeatedTest(5)
    void concurrentInsertsOnOneThreadKeepExactlyOneClaimableTurn() throws Exception {
        TestSession session = TestSessions.create(clock);
        List<NewTurn> turns = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_INSERTS; i++) {
            turns.add(session.turn("消息 " + i));
        }

        List<EnqueueResult> results = runConcurrently(turns.stream()
                .map(turn -> (java.util.concurrent.Callable<EnqueueResult>)
                        () -> node.enqueuer().enqueue(turn))
                .toList());

        List<EnqueueResult.Enqueued> enqueued = results.stream()
                .filter(EnqueueResult.Enqueued.class::isInstance)
                .map(EnqueueResult.Enqueued.class::cast)
                .toList();
        assertThat(enqueued).hasSize(1);
        assertThat(results)
                .filteredOn(EnqueueResult.ThreadBusy.class::isInstance)
                .hasSize(CONCURRENT_INSERTS - 1);
        String turnId = enqueued.getFirst().turnId();
        assertThat(DbAssertions.list(
                        "SELECT id FROM rt.turns WHERE thread_id = ?", String.class, session.mainThreadId()))
                .containsExactly(turnId);
        assertThat(DbAssertions.list(
                        "SELECT count(*) FROM rt.turn_inputs i JOIN rt.turns t ON t.id = i.turn_id WHERE t.thread_id = ?",
                        Long.class,
                        session.mainThreadId()))
                .containsExactly(1L);
        assertThat(DbAssertions.list(
                        "SELECT max_running FROM rt.tenant_concurrency WHERE tenant_id = ?",
                        Integer.class,
                        session.tenantId()))
                .containsExactly(20);
        assertThat(node.wakeups()).hasValue(1);

        ClaimResult claim = node.claimer().claim();

        assertThat(claim).isInstanceOf(ClaimResult.Claimed.class);
        LeaseHandle lease = ((ClaimResult.Claimed) claim).lease();
        assertThat(lease.turn().turnId()).isEqualTo(turnId);
        String payload = node.queue().findInput(turnId).orElseThrow();
        assertThat(turns)
                .contains(new NewTurn(
                        session.tenantId(),
                        session.projectId(),
                        session.sessionId(),
                        session.mainThreadId(),
                        EngineJson.readInput("messages", payload),
                        0));
    }

    @Test
    void threadStaysBusyWhileItsTurnIsRunningOrAwaitingAction() {
        TestSession session = TestSessions.create(clock);
        node.enqueuer().enqueue(session.turn("第一条"));
        LeaseHandle first = ((ClaimResult.Claimed) node.claimer().claim()).lease();

        assertThat(node.enqueuer().enqueue(session.turn("运行中到达"))).isInstanceOf(EnqueueResult.ThreadBusy.class);

        node.transitions().finish(first.turn(), TurnOutcome.AWAITING_ACTION, null);
        first.release();
        assertThat(node.enqueuer().enqueue(session.turn("等待回应时到达"))).isInstanceOf(EnqueueResult.ThreadBusy.class);

        DbAssertions.update(
                "UPDATE rt.turns SET status = 'completed' WHERE id = ?",
                first.turn().turnId());
        assertThat(node.enqueuer().enqueue(session.turn("结束后到达"))).isInstanceOf(EnqueueResult.Enqueued.class);
    }

    @Test
    void finishedTurnFreesTheThreadForTheNextTurn() {
        TestSession session = TestSessions.create(clock);
        node.enqueuer().enqueue(session.turn("第一条"));
        LeaseHandle lease = ((ClaimResult.Claimed) node.claimer().claim()).lease();
        node.transitions().finish(lease.turn(), TurnOutcome.COMPLETED, StopReason.END_TURN);
        lease.release();

        assertThat(node.enqueuer().enqueue(session.turn("第二条"))).isInstanceOf(EnqueueResult.Enqueued.class);
    }

    @Test
    void enqueueInsideAnOuterTransactionPublishesOnlyAfterCommitAndRollsBackCompletely() {
        TestSession committed = TestSessions.create(clock);
        TestSession rolledBack = TestSessions.create(clock);

        RuntimeTestDatabase.transactions().executeWithoutResult(status -> {
            node.enqueuer().enqueue(committed.turn("外层事务中入队"));
            assertThat(node.wakeups()).as("外层事务提交前不发布唤醒").hasValue(0);
        });
        assertThat(node.wakeups()).hasValue(1);

        RuntimeTestDatabase.transactions().executeWithoutResult(status -> {
            node.enqueuer().enqueue(rolledBack.turn("将被回滚"));
            status.setRollbackOnly();
        });
        assertThat(node.wakeups()).hasValue(1);
        assertThat(DbAssertions.list(
                        "SELECT count(*) FROM rt.turns WHERE thread_id = ?", Long.class, rolledBack.mainThreadId()))
                .containsExactly(0L);
        assertThat(DbAssertions.running(rolledBack.tenantId())).as("计数行随外层事务回滚").isNull();
    }

    private static <T> List<T> runConcurrently(List<java.util.concurrent.Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (java.util.concurrent.Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
