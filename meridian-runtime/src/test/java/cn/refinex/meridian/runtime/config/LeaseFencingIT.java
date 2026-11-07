package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.LeaseLostException;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.time.MutableClock;
import cn.refinex.meridian.engine.agentscope.state.FencedAgentStateStore;
import cn.refinex.meridian.engine.spi.event.AgentOutputEvent;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.port.AgentStateRepository.StateFence;
import cn.refinex.meridian.engine.spi.port.AgentStateRepository.StateKey;
import cn.refinex.meridian.runtime.dispatch.app.ClaimResult;
import cn.refinex.meridian.runtime.dispatch.app.LeaseHandle;
import cn.refinex.meridian.runtime.dispatch.app.NodeLeaseRegistry;
import cn.refinex.meridian.runtime.dispatch.domain.RequeueReason;
import cn.refinex.meridian.runtime.dispatch.domain.TurnOutcome;
import cn.refinex.meridian.runtime.session.domain.NewThread;
import cn.refinex.meridian.runtime.session.infra.ThreadRepository;
import cn.refinex.meridian.runtime.stream.domain.AppendResult;
import cn.refinex.meridian.runtime.stream.domain.EventBatch;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.support.TestNode;
import cn.refinex.meridian.runtime.support.TestSessions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import cn.refinex.meridian.testsupport.TestDatabase;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import reactor.test.StepVerifier;

/**
 * T05-A05（P2 存储层）：失去租约的节点直接调用各写入方法——{@code save}、{@code saveIfVersion}（含无条件写）、{@code delete}、
 * 事件写入——全部被拒绝，数据库没有任何变化。状态写入走生产的 {@link FencedAgentStateStore}（engine-agentscope）加 runtime 的
 * {@code JdbcAgentStateRepository} 与 {@code NodeLeaseRegistry}；中断、出错、停机三条完整路径由 T06 补充。
 */
class LeaseFencingIT {

    private static final String KEY = "agent_state";

    private final MutableClock clock = MutableClock.at(Instant.parse("2100-01-01T00:00:00Z"));
    private TestNode nodeA;
    private TestNode nodeB;
    private TestSession session;
    private LeaseHandle leaseA;
    private FencedAgentStateStore storeA;
    private FencedAgentStateStore storeB;

    @BeforeEach
    void setUp() {
        RuntimeTestDatabase.truncate();
        nodeA = TestNode.create("node-a", clock);
        nodeB = TestNode.create("node-b", clock);
        session = TestSessions.create(clock);
        nodeA.enqueuer().enqueue(session.turn("fencing"));
        leaseA = ((ClaimResult.Claimed) nodeA.claimer().claim()).lease();
        storeA = new FencedAgentStateStore(nodeA.states(), nodeA.registry());
        storeB = new FencedAgentStateStore(nodeB.states(), nodeB.registry());
    }

    @Test
    void staleNodeWritesAreAllRejectedAndTheDatabaseIsUnchanged() {
        String user = session.userId();
        String thread = session.mainThreadId();
        storeA.save(user, thread, KEY, state("持有租约时写入"));
        storeA.save(user, thread, "memory_messages", List.of(new UserMessage("记忆")));
        appendLeased(nodeA, leaseA.epoch());

        // 节点 A 的执行仍在进行（尚未察觉），租约过期后被 B 回收并认领
        clock.advance(Duration.ofSeconds(31));
        assertThat(nodeB.reaper().reapExpired()).isEqualTo(1);
        LeaseHandle leaseB = ((ClaimResult.Claimed) nodeB.claimer().claim()).lease();
        assertThat(leaseB.epoch()).isEqualTo(2);
        assertThat(nodeA.registry().find(thread).orElseThrow().epoch())
                .as("A 的登记仍是 epoch 1，拒绝只能来自数据库")
                .isEqualTo(1);
        DatabaseSnapshot before = DatabaseSnapshot.take(session);

        List<ThrowingCallable> staleWrites = List.of(
                () -> storeA.save(user, thread, KEY, state("旧执行：save")),
                () -> storeA.saveIfVersion(user, thread, KEY, state("旧执行：版本匹配"), 1),
                () -> storeA.saveIfVersion(user, thread, KEY, state("旧执行：无条件"), AgentStateStore.UNVERSIONED),
                () -> storeA.saveIfVersion(user, thread, "fresh_key", state("旧执行：不存在才写"), 0),
                () -> storeA.save(user, thread, "memory_messages", List.of(new UserMessage("旧执行：列表"))),
                () -> storeA.delete(user, thread, KEY),
                () -> storeA.delete(user, thread));
        for (ThrowingCallable write : staleWrites) {
            assertThatThrownBy(write).isInstanceOf(LeaseLostException.class);
        }
        assertThat(nodeA.events()
                        .append(EventBatch.leased(
                                session.sessionId(),
                                thread,
                                leaseA.turn().turnId(),
                                leaseA.epoch(),
                                new AgentOutputEvent.Thinking("b", "旧执行：事件"))))
                .isEqualTo(new AppendResult.LeaseLost(leaseA.turn().turnId(), 1));
        StepVerifier.create(nodeA.eventSink()
                        .append(thread, new AgentOutputEvent.Thinking("b", "旧执行：端口"))
                        .contextWrite(PlatformContexts.write(contextOf(leaseA))))
                .expectError(LeaseLostException.class)
                .verify();

        assertThat(DatabaseSnapshot.take(session)).isEqualTo(before);

        // 新持有者照常写入，content_epoch 更新为 2
        storeB.save(user, thread, KEY, state("新持有者"));
        assertThat(nodeB.states().contentEpoch(user, thread)).hasValue(2);
        appendLeased(nodeB, leaseB.epoch());
    }

    @Test
    void portRejectsAFenceThatNamesAnotherNodesEpoch() {
        StateKey key = new StateKey(session.userId(), session.mainThreadId(), KEY);
        clock.advance(Duration.ofSeconds(31));
        nodeB.reaper().reapExpired();
        nodeB.claimer().claim();

        // epoch 2 是对的，但持有者是 node-b：lease_owner 条件拒绝 node-a
        assertThatThrownBy(() -> nodeA.states().save(key, "{}", new StateFence(session.mainThreadId(), 2, true)))
                .isInstanceOf(LeaseLostException.class);
        assertThat(nodeA.states().find(key)).isEmpty();
    }

    @Test
    void nodeWithoutTheLeaseIsRejectedBeforeTouchingTheDatabase() {
        FencedAgentStateStore storeC =
                new FencedAgentStateStore(TestNode.create("node-c", clock).states(), new NodeLeaseRegistry());

        assertThatThrownBy(() -> storeC.save(session.userId(), session.mainThreadId(), KEY, state("没有租约")))
                .isInstanceOf(LeaseLostException.class)
                .hasMessageContaining("本节点没有线程");
    }

    @Test
    void childThreadWritesAreFencedByTheParentTurnLease() {
        String child = Ids.newId(IdPrefix.THREAD);
        PlatformContexts.run(
                session.context(),
                () -> new ThreadRepository(RuntimeTestDatabase.dataSource())
                        .insert(
                                new NewThread(
                                        child,
                                        session.sessionId(),
                                        session.mainThreadId(),
                                        "toolu_spawn",
                                        "researcher"),
                                clock.instant()));
        nodeA.registry().attachChildThread(child, session.mainThreadId());

        storeA.save(session.userId(), child, KEY, state("子线程"));
        assertThat(nodeA.states().contentEpoch(session.userId(), child)).hasValue(1);
        assertThat(rows(child)).first().satisfies(row -> {
            assertThat(row.get("tenant_id")).isEqualTo(session.tenantId());
            assertThat(row.get("project_id")).isEqualTo(session.projectId());
        });

        clock.advance(Duration.ofSeconds(31));
        nodeB.reaper().reapExpired();
        nodeB.claimer().claim();

        assertThatThrownBy(() -> storeA.save(session.userId(), child, KEY, state("旧父轮次")))
                .isInstanceOf(LeaseLostException.class);
    }

    @Test
    void permissionInstallWritesDoNotMoveTheContentEpoch() {
        String user = session.userId();
        String thread = session.mainThreadId();
        storeA.save(user, thread, KEY, state("epoch 1 的内容"));
        nodeA.transitions().requeue(leaseA.turn(), RequeueReason.NODE_SHUTDOWN);
        leaseA.release();
        LeaseHandle second = ((ClaimResult.Claimed) nodeA.claimer().claim()).lease();
        assertThat(second.epoch()).isEqualTo(2);

        nodeA.registry().markPermissionInstall(thread, true);
        try {
            storeA.save(user, thread, KEY, state("权限安装"));
        } finally {
            nodeA.registry().markPermissionInstall(thread, false);
        }
        assertThat(nodeA.states().contentEpoch(user, thread)).as("安装写入不是内容写入").hasValue(1);
        assertThat(storeA.getVersioned(user, thread, KEY, AgentState.class).version())
                .isEqualTo(2);

        assertThat(nodeA.states().contentEpochOfTurn(thread, second.turn().turnId()))
                .as("安装写入不改变内容所属的轮次与 epoch")
                .hasValue(1);

        storeA.save(user, thread, KEY, state("epoch 2 的内容"));
        assertThat(nodeA.states().contentEpoch(user, thread)).hasValue(2);
        assertThat(nodeA.states().contentEpochOfTurn(thread, second.turn().turnId()))
                .hasValue(2);
    }

    @Test
    void contentWritesRecordTheirTurnBecauseEpochsRestartEveryTurn() {
        String user = session.userId();
        String thread = session.mainThreadId();
        storeA.save(user, thread, KEY, state("第一轮 epoch 1 的内容"));
        nodeA.transitions().finish(leaseA.turn(), TurnOutcome.COMPLETED, StopReason.END_TURN);
        leaseA.release();
        nodeA.enqueuer().enqueue(session.turn("第二轮"));
        LeaseHandle next = ((ClaimResult.Claimed) nodeA.claimer().claim()).lease();
        String nextTurn = next.turn().turnId();
        assertThat(next.epoch()).as("新一轮的 epoch 从 1 重新开始").isEqualTo(1);

        assertThat(nodeA.states().contentEpoch(user, thread))
                .as("只看 content_epoch 会把上一轮的写入当成本轮 epoch 1 的写入")
                .hasValue(1);
        assertThat(nodeA.states().contentEpochOfTurn(thread, nextTurn)).isEmpty();
        assertThat(nodeA.states().contentEpochOfTurn(thread, leaseA.turn().turnId()))
                .hasValue(1);

        storeA.save(user, thread, KEY, state("第二轮 epoch 1 的内容"));
        assertThat(nodeA.states().contentEpochOfTurn(thread, nextTurn)).hasValue(1);
        assertThat(nodeA.states().contentEpochOfTurn(thread, leaseA.turn().turnId()))
                .as("同一个状态键被新一轮覆盖")
                .isEmpty();
    }

    @Test
    void versionedWritesFollowAgentScopeSemanticsForTheHolder() {
        String user = session.userId();
        String thread = session.mainThreadId();

        assertThat(storeA.saveIfVersion(user, thread, KEY, state("v1"), 0)).isEqualTo(1);
        assertThat(storeA.saveIfVersion(user, thread, KEY, state("又一次 v1"), 0))
                .as("期望不存在但已存在")
                .isEqualTo(AgentStateStore.UNVERSIONED);
        assertThat(storeA.saveIfVersion(user, thread, KEY, state("v2"), 1)).isEqualTo(2);
        assertThat(storeA.saveIfVersion(user, thread, KEY, state("过期的版本"), 1)).isEqualTo(AgentStateStore.UNVERSIONED);
        assertThat(storeA.saveIfVersion(user, thread, KEY, state("无条件"), AgentStateStore.UNVERSIONED))
                .isEqualTo(3);
        assertThat(storeA.getVersioned(user, thread, KEY, AgentState.class).version())
                .isEqualTo(3);
        assertThat(storeA.exists(user, thread)).isTrue();
        assertThat(storeA.listSessionIds(user)).containsExactly(thread);

        storeA.delete(user, thread);
        assertThat(storeA.exists(user, thread)).isFalse();
        assertThat(nodeA.states().contentEpoch(user, thread)).isEmpty();
    }

    private void appendLeased(TestNode node, long epoch) {
        LeaseHandle lease = node.registry().handle(session.mainThreadId()).orElseThrow();
        assertThat(node.events()
                        .append(EventBatch.leased(
                                session.sessionId(),
                                session.mainThreadId(),
                                lease.turn().turnId(),
                                epoch,
                                new AgentOutputEvent.Thinking("b", "持有者写入"))))
                .isInstanceOf(AppendResult.Appended.class);
    }

    private PlatformContext contextOf(LeaseHandle lease) {
        return session.context().toBuilder()
                .threadId(lease.threadId())
                .turnId(lease.turn().turnId())
                .leaseEpoch(lease.epoch())
                .build();
    }

    private static AgentState state(String text) {
        AgentState state = AgentState.builder().build();
        state.contextMutable().add(new UserMessage(text));
        return state;
    }

    private static List<Map<String, Object>> rows(String thread) {
        return JdbcClient.create(TestDatabase.migratorDataSource())
                .sql("SELECT * FROM rt.agent_states WHERE thread_id = ? ORDER BY state_key")
                .param(thread)
                .query()
                .listOfRows();
    }

    /** 会话相关的全部可写数据：智能体状态的每一行、事件序号与事件数。 */
    private record DatabaseSnapshot(List<Map<String, Object>> states, List<Long> lastSeq, List<Long> events) {

        static DatabaseSnapshot take(TestSession session) {
            return new DatabaseSnapshot(
                    JdbcClient.create(TestDatabase.migratorDataSource())
                            .sql("SELECT user_id, thread_id, state_key, state::text AS state, version, content_epoch,"
                                    + " updated_at FROM rt.agent_states WHERE thread_id = ? ORDER BY state_key")
                            .param(session.mainThreadId())
                            .query()
                            .listOfRows(),
                    DbAssertions.list("SELECT last_seq FROM rt.sessions WHERE id = ?", Long.class, session.sessionId()),
                    DbAssertions.list(
                            "SELECT count(*) FROM rt.session_events WHERE session_id = ?",
                            Long.class,
                            session.sessionId()));
        }
    }
}
