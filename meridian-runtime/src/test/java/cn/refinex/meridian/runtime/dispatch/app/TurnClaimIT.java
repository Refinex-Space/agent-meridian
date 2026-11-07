package cn.refinex.meridian.runtime.dispatch.app;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.runtime.dispatch.domain.TurnOutcome;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.support.TestNode;
import cn.refinex.meridian.runtime.support.TestSessions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import cn.refinex.meridian.testsupport.TestDatabase;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * T05-A03 与 T05-A06：多个节点、多个 Worker 并发认领时同一轮次只被认领一次；满额租户的轮次不被认领、不影响其他租户；
 * 新租户不需要手工插入计数行；名额竞争失败时整个认领事务回滚；本节点上一次执行结束前不会再认领同一线程。
 */
class TurnClaimIT {

    private final MutableClock clock = MutableClock.at(Instant.parse("2100-01-01T00:00:00Z"));

    @BeforeEach
    void setUp() {
        RuntimeTestDatabase.truncate();
    }

    @RepeatedTest(3)
    void eachTurnIsClaimedByExactlyOneWorkerAcrossNodes() throws Exception {
        List<String> tenants = List.of(newTenant(), newTenant(), newTenant());
        TestNode seeder = TestNode.create("seeder", clock);
        List<String> expected = new ArrayList<>();
        for (String tenant : tenants) {
            for (int i = 0; i < 10; i++) {
                EnqueueResult result = seeder.enqueuer()
                        .enqueue(TestSessions.create(tenant, clock).turn("并发认领"));
                expected.add(((EnqueueResult.Enqueued) result).turnId());
            }
        }
        List<TestNode> nodes = List.of(
                TestNode.create("node-a", clock),
                TestNode.create("node-b", clock),
                TestNode.create("node-c", clock),
                TestNode.create("node-d", clock));
        Map<String, String> claimedBy = new ConcurrentHashMap<>();
        AtomicInteger duplicates = new AtomicInteger();

        ExecutorService workers = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                TestNode node = nodes.get(i % nodes.size());
                futures.add(workers.submit(() -> {
                    start.await();
                    while (claimedBy.size() < expected.size()) {
                        ClaimResult result = node.claimer().claim();
                        if (result instanceof ClaimResult.Claimed(LeaseHandle lease)
                                && claimedBy.putIfAbsent(
                                                lease.turn().turnId(),
                                                node.nodeId().value())
                                        != null) {
                            duplicates.incrementAndGet();
                        } else if (result instanceof ClaimResult.NoneAvailable) {
                            break;
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            workers.shutdownNow();
        }

        assertThat(duplicates).hasValue(0);
        assertThat(claimedBy.keySet()).containsExactlyInAnyOrderElementsOf(expected);
        for (Map.Entry<String, String> entry : claimedBy.entrySet()) {
            Map<String, Object> turn = DbAssertions.turn(entry.getKey());
            assertThat(turn.get("status")).isEqualTo("running");
            assertThat(turn.get("lease_owner")).isEqualTo(entry.getValue());
            assertThat(turn.get("lease_epoch")).isEqualTo(1L);
            assertThat(turn.get("claim_count")).isEqualTo(1);
        }
        for (String tenant : tenants) {
            assertThat(DbAssertions.running(tenant)).isEqualTo(10);
        }
        int registered = nodes.stream()
                .mapToInt(n -> n.registry().activeHandles().size())
                .sum();
        assertThat(registered).isEqualTo(expected.size());
    }

    @Test
    void fullTenantIsNotClaimedAndOtherTenantsAreUnaffected() throws Exception {
        String busyTenant = newTenant();
        String otherTenant = newTenant();
        TestNode nodeA =
                TestNode.create("node-a", clock, DispatchProperties.defaults().withDefaultTenantMaxRunning(2));
        TestNode nodeB = TestNode.create("node-b", clock);
        for (int i = 0; i < 5; i++) {
            nodeA.enqueuer().enqueue(TestSessions.create(busyTenant, clock).turn("满额租户"));
        }
        for (int i = 0; i < 3; i++) {
            nodeB.enqueuer().enqueue(TestSessions.create(otherTenant, clock).turn("其他租户"));
        }

        List<LeaseHandle> claimed = claimAll(List.of(nodeA, nodeB));

        assertThat(claimed)
                .extracting(lease -> lease.turn().tenantId())
                .containsOnly(busyTenant, otherTenant)
                .filteredOn(busyTenant::equals)
                .hasSize(2);
        assertThat(claimed)
                .filteredOn(lease -> lease.turn().tenantId().equals(otherTenant))
                .hasSize(3);
        assertThat(DbAssertions.running(busyTenant)).isEqualTo(2);
        assertThat(nodeA.claimer().claim()).isInstanceOf(ClaimResult.NoneAvailable.class);
        assertThat(DbAssertions.list(
                        "SELECT count(*) FROM rt.turns WHERE tenant_id = ? AND status = 'queued' AND lease_epoch = 0",
                        Long.class,
                        busyTenant))
                .containsExactly(3L);

        LeaseHandle finished = claimed.stream()
                .filter(lease -> lease.turn().tenantId().equals(busyTenant))
                .findFirst()
                .orElseThrow();
        TestNode owner = nodeOf(finished, nodeA, nodeB);
        owner.transitions().finish(finished.turn(), TurnOutcome.COMPLETED, StopReason.END_TURN);
        finished.release();

        ClaimResult next = nodeB.claimer().claim();
        assertThat(next).isInstanceOf(ClaimResult.Claimed.class);
        assertThat(((ClaimResult.Claimed) next).lease().turn().tenantId()).isEqualTo(busyTenant);
    }

    @Test
    void newTenantIsClaimableWithoutManualCounterRow() {
        TestNode node = TestNode.create("node-a", clock);
        TestSession session = TestSessions.create(clock);
        assertThat(DbAssertions.running(session.tenantId())).isNull();

        node.enqueuer().enqueue(session.turn("新租户"));
        ClaimResult result = node.claimer().claim();

        assertThat(result).isInstanceOf(ClaimResult.Claimed.class);
        assertThat(DbAssertions.running(session.tenantId())).isEqualTo(1);
    }

    @Test
    void turnsAreClaimedByPriorityThenAgeAndNotBeforeIsRespected() {
        TestNode node = TestNode.create("node-a", clock);
        TestSession older = TestSessions.create(clock);
        TestSession newer = TestSessions.create(clock);
        TestSession urgent = TestSessions.create(clock);
        String olderTurn = enqueued(node, older.turn("先到"));
        clock.advance(Duration.ofSeconds(1));
        String newerTurn = enqueued(node, newer.turn("后到"));
        String urgentTurn = enqueued(node, urgent.turn("优先"));
        DbAssertions.update("UPDATE rt.turns SET priority = -1 WHERE id = ?", urgentTurn);
        DbAssertions.update("UPDATE rt.turns SET not_before = not_before + interval '1 hour' WHERE id = ?", olderTurn);

        assertThat(claimedTurnId(node)).isEqualTo(urgentTurn);
        assertThat(claimedTurnId(node)).isEqualTo(newerTurn);
        assertThat(node.claimer().claim()).as("先到的轮次还没到最早认领时间").isInstanceOf(ClaimResult.NoneAvailable.class);

        clock.advance(Duration.ofHours(1));
        assertThat(claimedTurnId(node)).isEqualTo(olderTurn);
    }

    @Test
    void losingTheQuotaRaceRollsBackTheWholeClaim() throws Exception {
        TestNode node =
                TestNode.create("node-a", clock, DispatchProperties.defaults().withDefaultTenantMaxRunning(1));
        TestSession session = TestSessions.create(clock);
        String turnId = enqueued(node, session.turn("竞争名额"));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection blocker = TestDatabase.migratorDataSource().getConnection();
                Statement statement = blocker.createStatement()) {
            // 另一个 Worker 正在占用该租户的名额：先锁住计数行，认领的第 1 步不锁计数行，照常选中轮次，第 2 步在行锁上等待
            blocker.setAutoCommit(false);
            statement.execute(
                    "SELECT 1 FROM rt.tenant_concurrency WHERE tenant_id = '" + session.tenantId() + "' FOR UPDATE");
            Future<ClaimResult> claim = executor.submit(() -> node.claimer().claim());
            awaitBlockedOnLock();
            statement.execute("UPDATE rt.tenant_concurrency SET running = max_running WHERE tenant_id = '"
                    + session.tenantId() + "'");
            blocker.commit();

            assertThat(claim.get(30, TimeUnit.SECONDS)).isEqualTo(new ClaimResult.QuotaContended(session.tenantId()));
        } finally {
            executor.shutdownNow();
        }

        Map<String, Object> turn = DbAssertions.turn(turnId);
        assertThat(turn.get("status")).isEqualTo("queued");
        assertThat(turn.get("lease_epoch")).isEqualTo(0L);
        assertThat(turn.get("claim_count")).isEqualTo(0);
        assertThat(turn.get("lease_owner")).isNull();
        assertThat(node.registry().heldThreadIds()).as("预留的条目随回滚取消").isEmpty();
    }

    @Test
    void sameNodeDoesNotReclaimAThreadUntilItsPreviousExecutionEnds() {
        TestNode nodeA = TestNode.create("node-a", clock);
        TestNode nodeB = TestNode.create("node-b", clock);
        TestSession session = TestSessions.create(clock);
        String turnId = enqueued(nodeA, session.turn("同节点重新认领"));
        LeaseHandle stale = ((ClaimResult.Claimed) nodeA.claimer().claim()).lease();

        // 节点 A 的执行仍在进行（例如长时间停顿），租约过期后被回收，轮次回到 queued
        clock.advance(Duration.ofSeconds(31));
        assertThat(nodeB.reaper().reapExpired()).isEqualTo(1);
        assertThat(DbAssertions.turn(turnId).get("status")).isEqualTo("queued");

        assertThat(nodeA.claimer().claim()).as("本节点上一次执行还没结束，不认领同一线程").isInstanceOf(ClaimResult.NoneAvailable.class);
        assertThat(nodeA.registry().find(session.mainThreadId()).orElseThrow().epoch())
                .as("条目仍是旧执行的 epoch，残余写入不会借用新 epoch")
                .isEqualTo(1);

        stale.release();
        LeaseHandle fresh = ((ClaimResult.Claimed) nodeA.claimer().claim()).lease();

        assertThat(fresh.turn().turnId()).isEqualTo(turnId);
        assertThat(fresh.epoch()).isEqualTo(2);
        assertThat(fresh.turn().isRecovery()).isTrue();
    }

    @Test
    void otherNodesMayClaimTheThreadWhileTheStaleExecutionIsStillRegistered() {
        TestNode nodeA = TestNode.create("node-a", clock);
        TestNode nodeB = TestNode.create("node-b", clock);
        TestSession session = TestSessions.create(clock);
        enqueued(nodeA, session.turn("跨节点续跑"));
        nodeA.claimer().claim();
        clock.advance(Duration.ofSeconds(31));
        nodeA.reaper().reapExpired();

        LeaseHandle onB = ((ClaimResult.Claimed) nodeB.claimer().claim()).lease();

        assertThat(onB.epoch()).isEqualTo(2);
        assertThat(DbAssertions.turn(onB.turn().turnId()).get("lease_owner")).isEqualTo("node-b");
    }

    private static List<LeaseHandle> claimAll(List<TestNode> nodes) throws Exception {
        List<LeaseHandle> claimed = java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService workers = Executors.newFixedThreadPool(nodes.size() * 2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < nodes.size() * 2; i++) {
                TestNode node = nodes.get(i % nodes.size());
                futures.add(workers.submit(() -> {
                    while (true) {
                        ClaimResult result = node.claimer().claim();
                        if (result instanceof ClaimResult.Claimed(LeaseHandle lease)) {
                            claimed.add(lease);
                        } else if (result instanceof ClaimResult.NoneAvailable) {
                            return null;
                        }
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            workers.shutdownNow();
        }
        return claimed;
    }

    private static TestNode nodeOf(LeaseHandle lease, TestNode... nodes) {
        String owner = (String) DbAssertions.turn(lease.turn().turnId()).get("lease_owner");
        for (TestNode node : nodes) {
            if (node.nodeId().value().equals(owner)) {
                return node;
            }
        }
        throw new IllegalStateException("没有节点持有 " + lease.turn().turnId());
    }

    private static String enqueued(TestNode node, cn.refinex.meridian.runtime.dispatch.domain.NewTurn turn) {
        return ((EnqueueResult.Enqueued) node.enqueuer().enqueue(turn)).turnId();
    }

    private static String claimedTurnId(TestNode node) {
        return ((ClaimResult.Claimed) node.claimer().claim()).lease().turn().turnId();
    }

    private static String newTenant() {
        return Ids.newId(IdPrefix.TENANT);
    }

    /** 同步点：等到认领事务在计数行的行锁上等待（pg_locks 中出现未授予的锁）。 */
    private static void awaitBlockedOnLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            List<Long> waiting = DbAssertions.list("SELECT count(*) FROM pg_locks WHERE NOT granted", Long.class);
            if (waiting.getFirst() > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("认领事务没有在计数行的行锁上等待");
    }
}
