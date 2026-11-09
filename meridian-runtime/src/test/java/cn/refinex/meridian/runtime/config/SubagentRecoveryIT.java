package cn.refinex.meridian.runtime.config;

import static cn.refinex.meridian.runtime.config.WorkerFixture.LOOKUP;
import static cn.refinex.meridian.runtime.config.WorkerFixture.message;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.context.Interaction;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.error.LeaseLostException;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.event.UserEvent.Delivery;
import cn.refinex.meridian.engine.spi.port.ThreadRegistryPort.ThreadStatus;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort.InvocationStart;
import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import cn.refinex.meridian.runtime.dispatch.app.DispatchProperties;
import cn.refinex.meridian.runtime.session.app.DatabaseThreadRegistry;
import cn.refinex.meridian.runtime.session.app.TurnExecution;
import cn.refinex.meridian.runtime.session.app.TurnResult;
import cn.refinex.meridian.runtime.session.infra.ThreadRepository;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 子线程与崩溃恢复（T13，ADR-0040）：子线程的行与 {@code session.thread_*} 事件以父轮次的租约写入；子线程上的工具调用记在父轮次下，
 * 但父线程的恢复只看父线程自己的调用——子线程不会被恢复，父线程看到的是 {@code spawn_subagent} 本身。
 */
class SubagentRecoveryIT {

    private static final Duration LEASE_EXPIRY =
            DispatchProperties.defaults().leaseTtl().plusSeconds(1);

    @AfterEach
    void resetGracefulShutdown() {
        GracefulShutdownManager.getInstance().resetForTesting();
    }

    @Test
    void childThreadCallsDoNotTakePartInTheParentRecoveryAndStaleEpochCannotCreateThreads() throws Exception {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.toolCall("call_l", LOOKUP, Map.of("text", "订单")))
                .respond(r -> r.text("已恢复"))
                .build());
        WorkerNode nodeA = fixture.node("node-a");
        WorkerNode nodeB = fixture.node("node-b");
        TestSession session = fixture.session();
        DatabaseThreadRegistry registry = registry(nodeA, fixture);
        String childThread;
        PlatformContext parent;
        fixture.lookupCalls.close();
        try {
            fixture.post(nodeA, session, message("查订单", Delivery.QUEUE));
            TurnExecution killed = nodeA.claim();
            killed.run().subscribe();
            fixture.lookupCalls.awaitEntered();
            parent = session.context().toBuilder()
                    .threadId(session.mainThreadId())
                    .turnId((String) WorkerFixture.latestTurn(session).get("id"))
                    .leaseEpoch(1L)
                    .interaction(Interaction.ATTENDED)
                    .build();

            // 父线程执行中拉起子线程：线程行、thread_created 与状态事件都以父轮次的租约写入
            childThread = registry.createChildThread(session.mainThreadId(), "call_spawn", "researcher")
                    .contextWrite(PlatformContexts.write(parent))
                    .block();
            registry.updateStatus(childThread, ThreadStatus.RUNNING, null)
                    .contextWrite(PlatformContexts.write(parent))
                    .block();
            // 子线程上一个状态未知的 non_idempotent 调用（进程在它执行中被强杀）
            PlatformContext child = parent.toBuilder().threadId(childThread).build();
            nodeA.ledger()
                    .recordStart(new InvocationStart(
                            childThread,
                            "call_child_bash",
                            PlatformToolNames.BASH,
                            SideEffect.NON_IDEMPOTENT,
                            session.sessionId() + ":call_child_bash",
                            Map.of("command", "make deploy")))
                    .contextWrite(PlatformContexts.write(child))
                    .block();

            killed.cancel("模拟进程被强杀");
            fixture.clock.advance(LEASE_EXPIRY);
            WorkerFixture.reap(nodeB);
        } finally {
            fixture.lookupCalls.open();
        }

        assertThat(DbAssertions.row(
                        "SELECT parent_thread_id, parent_tool_use_id, agent_name, status FROM rt.threads WHERE id = ?",
                        childThread))
                .containsEntry("parent_thread_id", session.mainThreadId())
                .containsEntry("parent_tool_use_id", "call_spawn")
                .containsEntry("agent_name", "researcher")
                .as("父轮次被回收时子线程以 interrupted 结束，不会停在 running（R1 并发审查）")
                .containsEntry("status", "idle");
        assertThat(DbAssertions.list(
                        "SELECT type FROM rt.session_events WHERE session_id = ? AND thread_id = ? ORDER BY seq",
                        String.class,
                        session.sessionId(),
                        childThread))
                .containsExactly(
                        "session.thread_created", "session.thread_status_running", "session.thread_status_idle");
        assertThat(DbAssertions.list(
                        "SELECT data->>'stop_reason' FROM rt.session_events WHERE session_id = ? AND thread_id = ?"
                                + " AND type = 'session.thread_status_idle'",
                        String.class,
                        session.sessionId(),
                        childThread))
                .containsExactly("interrupted");

        // 旧 epoch 的执行已失去租约：不能再创建子线程，什么都不写
        PlatformContext stale = parent;
        assertThatThrownBy(() -> registry.createChildThread(session.mainThreadId(), "call_late", "researcher")
                        .contextWrite(PlatformContexts.write(stale))
                        .block())
                .isInstanceOf(LeaseLostException.class);
        assertThat(DbAssertions.list(
                        "SELECT count(*) FROM rt.threads WHERE session_id = ?", Long.class, session.sessionId()))
                .containsExactly(2L);

        TurnResult result = nodeB.claimAndRun();

        assertThat(result)
                .extracting(r -> ((TurnResult.Finished) r).stopReason())
                .isEqualTo(StopReason.END_TURN);
        assertThat(fixture.actions(session))
                .as("子线程上状态未知的 non_idempotent 调用不产生恢复确认")
                .isEmpty();
        String note = fixture.model.requests().getLast().messages().getLast().getTextContent();
        assertThat(note).startsWith("[平台恢复说明]").contains(LOOKUP).doesNotContain("call_child_bash");
    }

    private static DatabaseThreadRegistry registry(WorkerNode node, WorkerFixture fixture) {
        return new DatabaseThreadRegistry(
                new ThreadRepository(RuntimeTestDatabase.dataSource()),
                node.node().events(),
                node.node().eventSink(),
                new TransactionTemplate(new DataSourceTransactionManager(RuntimeTestDatabase.dataSource())),
                fixture.clock);
    }
}
