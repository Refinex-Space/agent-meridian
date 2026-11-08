package cn.refinex.meridian.runtime.config;

import static cn.refinex.meridian.runtime.config.WorkerFixture.LOOKUP;
import static cn.refinex.meridian.runtime.config.WorkerFixture.WRITE;
import static cn.refinex.meridian.runtime.config.WorkerFixture.message;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.spi.event.PendingAction;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.event.UserEvent;
import cn.refinex.meridian.engine.spi.event.UserEvent.Delivery;
import cn.refinex.meridian.engine.spi.event.UserEvent.RecoveryDecision;
import cn.refinex.meridian.runtime.approval.domain.StoredPendingAction;
import cn.refinex.meridian.runtime.dispatch.app.DispatchProperties;
import cn.refinex.meridian.runtime.dispatch.domain.TurnOutcome;
import cn.refinex.meridian.runtime.session.app.TurnExecution;
import cn.refinex.meridian.runtime.session.app.TurnResult;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import io.agentscope.core.message.Msg;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 恢复确认的回应（架构设计「待处理动作」「崩溃恢复」）：{@code retry} 由平台在 Java 侧用原幂等键重新执行，结果写进恢复说明；
 * {@code skip} 只在说明里注明跳过。恢复方式为 {@code replay_input}（进程被强杀，状态里没有本轮的输入）时重放原轮输入，否则会丢掉
 * 用户消息（登记事项 D13，ADR-0031）。
 */
class RecoveryConfirmationIT {

    private static final Duration LEASE_EXPIRY =
            DispatchProperties.defaults().leaseTtl().plusSeconds(1);

    @AfterEach
    void resetGracefulShutdown() {
        GracefulShutdownManager.getInstance().resetForTesting();
    }

    @Test
    void retryReExecutesTheCallWithTheOriginalKeyAndReplaysTheOriginalInput() throws Exception {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.toolCall("call_w", WRITE, Map.of("text", "扣款")))
                .respond(r -> r.text("扣款已完成"))
                .build());
        WorkerNode nodeA = fixture.node("node-a");
        WorkerNode nodeB = fixture.node("node-b");
        TestSession session = fixture.session();
        StoredPendingAction action;
        fixture.writeCalls.close();
        try {
            fixture.post(nodeA, session, message("扣一笔款", Delivery.QUEUE));
            crashDuringTheTool(fixture, nodeA, nodeB);
            assertThat(nodeB.claimAndRun())
                    .extracting(r -> ((TurnResult.Finished) r).outcome())
                    .isEqualTo(TurnOutcome.AWAITING_ACTION);
            action = fixture.actions(session).getFirst();
            assertThat(action.type()).isEqualTo(PendingAction.Type.RECOVERY_CONFIRMATION);
        } finally {
            fixture.writeCalls.open();
        }

        fixture.post(nodeB, session, new UserEvent.RecoveryConfirmation(action.id(), RecoveryDecision.RETRY));
        Map<String, Object> resumption = WorkerFixture.latestTurn(session);
        assertThat(resumption).as("replay_input：重放原轮的输入").containsEntry("kind", "messages");
        TurnResult result = nodeB.claimAndRun();

        assertThat(result)
                .extracting(r -> ((TurnResult.Finished) r).stopReason())
                .isEqualTo(StopReason.END_TURN);
        assertThat(fixture.writeCalls.toolUseIds()).as("原调用与平台的重试").containsExactly("call_w", "call_w");
        assertThat(DbAssertions.list(
                        "SELECT idempotency_key FROM rt.tool_invocations WHERE turn_id = ?",
                        String.class,
                        resumption.get("id")))
                .containsExactly("idem-call_w");
        List<Msg> replayed = fixture.model.request(1).messages();
        assertThat(replayed.stream()
                        .filter(m -> "扣一笔款".equals(m.getTextContent()))
                        .count())
                .as("用户消息随输入重放，只出现一次")
                .isEqualTo(1);
        assertThat(replayed.getLast().getTextContent())
                .startsWith("[平台恢复说明]")
                .contains(WRITE + "（call_w）：已按原幂等键重新执行。结果摘要：write_record:扣款");
    }

    @Test
    void skipResumesTheSavedStateWithTheCallMarkedAsSkipped() throws Exception {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.toolCall("call_l", LOOKUP, Map.of("text", "余额")))
                .respond(r -> r.toolCall("call_w", WRITE, Map.of("text", "扣款")))
                .respond(r -> r.text("扣款已跳过，请人工核对"))
                .build());
        WorkerNode nodeA = fixture.node("node-a");
        WorkerNode nodeB = fixture.node("node-b");
        TestSession session = fixture.session();
        StoredPendingAction action;
        fixture.writeCalls.close();
        try {
            fixture.post(nodeA, session, message("查余额后扣款", Delivery.QUEUE));
            crashDuringTheTool(fixture, nodeA, nodeB);
            nodeB.claimAndRun();
            action = fixture.actions(session).getFirst();
        } finally {
            fixture.writeCalls.open();
        }

        fixture.post(nodeB, session, new UserEvent.RecoveryConfirmation(action.id(), RecoveryDecision.SKIP));
        assertThat(WorkerFixture.latestTurn(session))
                .as("resume_saved_state：只有恢复说明")
                .containsEntry("kind", "recovery");
        TurnResult result = nodeB.claimAndRun();

        assertThat(result)
                .extracting(r -> ((TurnResult.Finished) r).stopReason())
                .isEqualTo(StopReason.END_TURN);
        assertThat(fixture.writeCalls.toolUseIds()).as("跳过，不重新执行").containsExactly("call_w");
        assertThat(fixture.model.request(2).messages().getLast().getTextContent())
                .startsWith("[平台恢复说明]")
                .contains(LOOKUP + "（call_l）：已成功执行")
                .contains(WRITE + "（call_w）：已跳过");
    }

    /** 在工具执行中途强杀节点 A，时钟推进到租约过期，节点 B 回收。 */
    private static void crashDuringTheTool(WorkerFixture fixture, WorkerNode nodeA, WorkerNode nodeB)
            throws InterruptedException {
        TurnExecution killed = nodeA.claim();
        killed.run().subscribe();
        fixture.writeCalls.awaitEntered();
        killed.cancel("模拟进程被强杀");
        fixture.clock.advance(LEASE_EXPIRY);
        WorkerFixture.reap(nodeB);
    }
}
