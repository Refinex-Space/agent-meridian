package cn.refinex.meridian.runtime.dispatch.app;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.runtime.dispatch.domain.ClaimedTurn;
import cn.refinex.meridian.runtime.dispatch.domain.LeaseLoss;
import cn.refinex.meridian.runtime.dispatch.domain.RequeueReason;
import cn.refinex.meridian.runtime.dispatch.domain.TurnOutcome;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.stream.infra.EngineJson;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.FailingDataSource;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.support.TestNode;
import cn.refinex.meridian.runtime.support.TestSessions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * T05-A04 与租约的其余行为：回收器在一个周期内回收过期租约（可控时钟），续租推迟到期并回调中断请求与租约丢失，
 * 置回队列的三种原因按架构设计区分恢复次数，结束轮次释放名额，过期的执行方无法再迁移状态。
 */
class LeaseLifecycleIT {

    private static final Instant START = Instant.parse("2100-01-01T00:00:00Z");

    private final MutableClock clock = MutableClock.at(START);
    private TestNode nodeA;
    private TestNode nodeB;

    @BeforeEach
    void setUp() {
        RuntimeTestDatabase.truncate();
        nodeA = TestNode.create("node-a", clock);
        nodeB = TestNode.create("node-b", clock);
    }

    @Test
    void reaperRequeuesAnExpiredLeaseWithinOneCycle() {
        TestSession session = TestSessions.create(clock);
        ClaimedTurn turn = claim(nodeA, session);
        assertThat(DbAssertions.running(session.tenantId())).isEqualTo(1);

        clock.advance(Duration.ofSeconds(29));
        assertThat(nodeB.reaper().reapExpired()).as("租约 30 秒内不回收").isZero();

        clock.advance(Duration.ofSeconds(2));
        assertThat(nodeB.reaper().reapExpired()).isEqualTo(1);

        Map<String, Object> row = DbAssertions.turn(turn.turnId());
        assertThat(row.get("status")).isEqualTo("queued");
        assertThat(row.get("recovery_attempt")).isEqualTo(1);
        assertThat(row.get("lease_epoch")).as("保留失败那次执行的 epoch").isEqualTo(1L);
        assertThat(row.get("lease_expires_at")).isNull();
        assertThat(DbAssertions.instant(row.get("not_before"))).isEqualTo(clock.instant());
        assertThat(DbAssertions.running(session.tenantId())).isZero();
        StoredEvent rescheduled = lastEvent(session);
        assertThat(rescheduled.type()).isEqualTo(EventType.SESSION_STATUS_RESCHEDULED);
        assertThat(EngineJson.readEvent(rescheduled.type(), rescheduled.data()))
                .isEqualTo(new SessionEvent.StatusRescheduled(SessionEvent.RescheduleReason.LEASE_EXPIRED));
        assertThat(rescheduled.data()).isEqualTo("{\"reason\": \"lease_expired\"}");
        assertThat(rescheduled.turnId()).isEqualTo(turn.turnId());
        assertThat(rescheduled.threadId()).isEqualTo(session.mainThreadId());
        assertThat(statusOf("sessions", session.sessionId())).isEqualTo("rescheduled");
        assertThat(statusOf("threads", session.mainThreadId())).isEqualTo("rescheduled");
        assertThat(nodeB.reaper().reapExpired()).as("同一轮次只回收一次").isZero();

        LeaseHandle recovered = ((ClaimResult.Claimed) nodeB.claimer().claim()).lease();
        assertThat(recovered.epoch()).isEqualTo(2);
        assertThat(recovered.turn().recoveryAttempt()).isEqualTo(1);
    }

    @Test
    void concurrentReapersOnSeveralNodesReapEachTurnOnce() throws Exception {
        List<TestSession> sessions = new ArrayList<>();
        String tenant = TestSessions.create(clock).tenantId();
        for (int i = 0; i < 6; i++) {
            TestSession session = TestSessions.create(tenant, clock);
            sessions.add(session);
            claim(nodeA, session);
        }
        clock.advance(Duration.ofSeconds(31));
        List<TestNode> reapers = List.of(nodeA, nodeB, TestNode.create("node-c", clock));

        ExecutorService executor = Executors.newFixedThreadPool(reapers.size());
        CountDownLatch start = new CountDownLatch(1);
        int reaped = 0;
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (TestNode node : reapers) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return node.reaper().reapExpired();
                }));
            }
            start.countDown();
            for (Future<Integer> future : futures) {
                reaped += future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(reaped).isEqualTo(sessions.size());
        assertThat(DbAssertions.running(tenant)).isZero();
        for (TestSession session : sessions) {
            assertThat(DbAssertions.list(
                            "SELECT count(*) FROM rt.session_events WHERE session_id = ? AND type = ?",
                            Long.class,
                            session.sessionId(),
                            EventType.SESSION_STATUS_RESCHEDULED.value()))
                    .containsExactly(1L);
        }
    }

    @Test
    void renewalPushesTheExpiryForwardSoTheReaperLeavesTheTurnAlone() {
        TestSession session = TestSessions.create(clock);
        ClaimedTurn turn = claim(nodeA, session);

        clock.advance(Duration.ofSeconds(10));
        nodeA.renewer().renewAll();
        clock.advance(Duration.ofSeconds(25));

        assertThat(nodeB.reaper().reapExpired()).isZero();
        assertThat(DbAssertions.instant(DbAssertions.turn(turn.turnId()).get("lease_expires_at")))
                .isEqualTo(START.plusSeconds(40));
    }

    @Test
    void renewalReportsAnInterruptRequestExactlyOnce() {
        TestSession session = TestSessions.create(clock);
        LeaseHandle lease = claimHandle(nodeA, session);
        RecordingListener listener = new RecordingListener();
        lease.listen(listener);
        Instant requestedAt = START.plusSeconds(3);
        DbAssertions.update(
                "UPDATE rt.turns SET interrupt_requested_at = ? WHERE id = ?",
                requestedAt.atOffset(java.time.ZoneOffset.UTC),
                lease.turn().turnId());

        clock.advance(Duration.ofSeconds(10));
        nodeA.renewer().renewAll();
        clock.advance(Duration.ofSeconds(10));
        nodeA.renewer().renewAll();

        assertThat(listener.events).containsExactly("interrupt@" + requestedAt);
    }

    @Test
    void renewalAfterTheTurnWasTakenOverReportsARevokedLease() {
        TestSession session = TestSessions.create(clock);
        LeaseHandle stale = claimHandle(nodeA, session);
        RecordingListener listener = new RecordingListener();
        stale.listen(listener);
        clock.advance(Duration.ofSeconds(31));
        nodeB.reaper().reapExpired();
        nodeB.claimer().claim();

        nodeA.renewer().renewAll();

        assertThat(listener.events).containsExactly("lost:" + LeaseLoss.REVOKED);
        assertThat(nodeA.registry().find(session.mainThreadId())).isEmpty();
        assertThat(nodeA.registry().heldThreadIds()).as("执行结束前条目仍在").contains(session.mainThreadId());
        assertThat(DbAssertions.turn(stale.turn().turnId()).get("lease_epoch")).isEqualTo(2L);
    }

    @Test
    void renewalFailingForMoreThanFifteenSecondsTellsTheExecutionToCancel() {
        FailingDataSource dataSource = new FailingDataSource(RuntimeTestDatabase.dataSource());
        TestNode node = TestNode.create("node-flaky", clock, DispatchProperties.defaults(), dataSource);
        LeaseHandle lease = claimHandle(node, TestSessions.create(clock));
        RecordingListener listener = new RecordingListener();
        lease.listen(listener);

        dataSource.failing(true);
        clock.advance(Duration.ofSeconds(10));
        node.renewer().renewAll();
        assertThat(listener.events).as("距上次成功续租 10 秒，仍在阈值内").isEmpty();

        dataSource.failing(false);
        node.renewer().renewAll();
        dataSource.failing(true);
        clock.advance(Duration.ofSeconds(10));
        node.renewer().renewAll();
        assertThat(listener.events).as("恢复后的成功续租重新计时").isEmpty();

        clock.advance(Duration.ofSeconds(6));
        node.renewer().renewAll();
        assertThat(listener.events).containsExactly("lost:" + LeaseLoss.RENEWAL_TIMEOUT);
        assertThat(lease.isLost()).isTrue();
    }

    @Test
    void requeueReasonsDecideWhetherTheRecoveryAttemptGrows() {
        TestSession session = TestSessions.create(clock);
        ClaimedTurn first = claim(nodeA, session);

        assertThat(nodeA.transitions().requeue(first, RequeueReason.RETRYABLE_ERROR))
                .isEqualTo(new TransitionResult.Done(first.turnId(), 1));
        assertThat(DbAssertions.running(session.tenantId())).isZero();
        assertThat(rescheduleReason(lastEvent(session))).isEqualTo(SessionEvent.RescheduleReason.RETRYABLE_ERROR);
        releaseAll(nodeA);

        ClaimedTurn second =
                ((ClaimResult.Claimed) nodeA.claimer().claim()).lease().turn();
        assertThat(nodeA.transitions().requeue(second, RequeueReason.NODE_SHUTDOWN))
                .isEqualTo(new TransitionResult.Done(second.turnId(), 1));
        assertThat(rescheduleReason(lastEvent(session))).isEqualTo(SessionEvent.RescheduleReason.NODE_SHUTDOWN);
        assertThat(DbAssertions.turn(second.turnId()).get("recovery_attempt"))
                .as("节点下线不增加恢复次数")
                .isEqualTo(1);
        assertThat(DbAssertions.running(session.tenantId())).isZero();
    }

    @Test
    void staleExecutionCannotRequeueOrFinishAndNothingChanges() {
        TestSession session = TestSessions.create(clock);
        ClaimedTurn stale = claim(nodeA, session);
        clock.advance(Duration.ofSeconds(31));
        nodeB.reaper().reapExpired();
        ClaimedTurn current =
                ((ClaimResult.Claimed) nodeB.claimer().claim()).lease().turn();
        long eventsBefore = eventCount(session);

        assertThat(nodeA.transitions().requeue(stale, RequeueReason.RETRYABLE_ERROR))
                .isEqualTo(new TransitionResult.LeaseLost(stale.turnId(), 1));
        assertThat(nodeA.transitions().finish(stale, TurnOutcome.COMPLETED, StopReason.END_TURN))
                .isEqualTo(new TransitionResult.LeaseLost(stale.turnId(), 1));

        Map<String, Object> row = DbAssertions.turn(current.turnId());
        assertThat(row.get("status")).isEqualTo("running");
        assertThat(row.get("lease_owner")).isEqualTo("node-b");
        assertThat(row.get("lease_epoch")).isEqualTo(2L);
        assertThat(DbAssertions.running(session.tenantId())).isEqualTo(1);
        assertThat(eventCount(session)).isEqualTo(eventsBefore);
    }

    @Test
    void finishReleasesTheSlotRecordsTheStopReasonAndIsFenced() {
        TestSession session = TestSessions.create(clock);
        ClaimedTurn turn = claim(nodeA, session);
        clock.advance(Duration.ofSeconds(5));

        assertThat(nodeA.transitions().finish(turn, TurnOutcome.COMPLETED, StopReason.END_TURN))
                .isEqualTo(new TransitionResult.Done(turn.turnId(), 0));

        Map<String, Object> row = DbAssertions.turn(turn.turnId());
        assertThat(row.get("status")).isEqualTo("completed");
        assertThat(row.get("stop_reason")).isEqualTo("end_turn");
        assertThat(DbAssertions.instant(row.get("finished_at"))).isEqualTo(START.plusSeconds(5));
        assertThat(DbAssertions.running(session.tenantId())).isZero();
        assertThat(nodeA.transitions().finish(turn, TurnOutcome.COMPLETED, StopReason.END_TURN))
                .isInstanceOf(TransitionResult.LeaseLost.class);
        assertThat(DbAssertions.running(session.tenantId())).as("计数不会被重复释放").isZero();
    }

    private static ClaimedTurn claim(TestNode node, TestSession session) {
        return claimHandle(node, session).turn();
    }

    private static LeaseHandle claimHandle(TestNode node, TestSession session) {
        node.enqueuer().enqueue(session.turn("租约"));
        ClaimResult result = node.claimer().claim();
        assertThat(result).isInstanceOf(ClaimResult.Claimed.class);
        LeaseHandle lease = ((ClaimResult.Claimed) result).lease();
        assertThat(lease.turn().sessionId()).isEqualTo(session.sessionId());
        return lease;
    }

    private static void releaseAll(TestNode node) {
        node.registry().activeHandles().forEach(LeaseHandle::release);
    }

    private static StoredEvent lastEvent(TestSession session) {
        return nodeEvents(session).getLast();
    }

    private static List<StoredEvent> nodeEvents(TestSession session) {
        return TestNode.create("reader", Clock0.INSTANCE).events().listAfter(session.sessionId(), 0, 1000);
    }

    private static long eventCount(TestSession session) {
        return nodeEvents(session).size();
    }

    private static SessionEvent.RescheduleReason rescheduleReason(StoredEvent event) {
        return ((SessionEvent.StatusRescheduled) EngineJson.readEvent(event.type(), event.data())).reason();
    }

    private static String statusOf(String table, String id) {
        return DbAssertions.list("SELECT status FROM rt." + table + " WHERE id = ?", String.class, id)
                .getFirst();
    }

    /** 只读事件时使用的时钟，读取不依赖时间。 */
    private static final class Clock0 {
        static final java.time.Clock INSTANCE = java.time.Clock.systemUTC();
    }

    /** 记录续租回调。 */
    private static final class RecordingListener implements LeaseListener {
        private final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void onInterruptRequested(Instant requestedAt) {
            events.add("interrupt@" + requestedAt);
        }

        @Override
        public void onLeaseLost(LeaseLoss loss) {
            events.add("lost:" + loss);
        }
    }
}
