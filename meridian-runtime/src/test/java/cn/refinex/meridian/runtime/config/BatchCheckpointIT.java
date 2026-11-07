package cn.refinex.meridian.runtime.config;

import static cn.refinex.meridian.runtime.config.TurnRecoveryIT.countUserText;
import static cn.refinex.meridian.runtime.config.TurnRecoveryIT.meter;
import static cn.refinex.meridian.runtime.config.TurnRecoveryIT.toolResultIds;
import static cn.refinex.meridian.runtime.config.TurnRecoveryIT.toolUseIds;
import static cn.refinex.meridian.runtime.config.WorkerFixture.LOOKUP;
import static cn.refinex.meridian.runtime.config.WorkerFixture.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.event.UserEvent.Delivery;
import cn.refinex.meridian.runtime.dispatch.domain.TurnOutcome;
import cn.refinex.meridian.runtime.session.app.TurnExecution;
import cn.refinex.meridian.runtime.session.app.TurnResult;
import cn.refinex.meridian.runtime.session.app.WorkerProperties;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import io.agentscope.core.message.Msg;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * T06-A05（M0 验证 P3）：每批工具执行完成后调用 {@code saveAgentState} 得到的 checkpoint 是否一致——强杀后从它续跑，tool_use 与
 * tool_result 配对，已完成的工具不再执行。对照组关闭批次 checkpoint。结论与数据见 {@code docs/poc/P03-批次checkpoint.md}。
 *
 * <p>强杀点：第二次模型调用开始时（第一批工具已完成、checkpoint 已保存），或第二批工具执行中途。强杀即取消执行、不做任何收尾，
 * 时钟推进 31 秒后另一节点回收并认领。
 */
class BatchCheckpointIT {

    private static final String USER_TEXT = "并行查 a、写 b";
    private static final Duration LEASE_EXPIRY = Duration.ofSeconds(31);

    @Test
    void killAfterABatchResumesFromTheCheckpointWithPairedToolResults() {
        Recovered recovered = killDuringTheSecondModelCall(WorkerProperties.defaults());

        assertThat(recovered.result)
                .isEqualTo(new TurnResult.Finished(recovered.turnId, TurnOutcome.COMPLETED, StopReason.END_TURN));
        assertThat(meter(recovered.nodeB, "meridian.turn.recoveries", "mode", "recovery_only"))
                .as("本轮写过内容（批次 checkpoint），不重放输入")
                .isEqualTo(1.0);
        List<Msg> request = recovered.fixture.model.request(2).messages();
        assertThat(countUserText(request, USER_TEXT)).isEqualTo(1);
        assertThat(toolUseIds(request)).containsExactlyInAnyOrder("call_a", "call_b");
        assertThat(toolResultIds(request))
                .as("checkpoint 中 tool_use 与 tool_result 一一配对")
                .containsExactlyInAnyOrderElementsOf(toolUseIds(request));
        assertThat(request.getLast().getTextContent()).contains(LOOKUP + "（call_a）：已成功执行", WRITE + "（call_b）：已成功执行");
        assertThat(recovered.fixture.lookupCalls.toolUseIds()).containsExactly("call_a");
        assertThat(recovered.fixture.writeCalls.toolUseIds())
                .as("non_idempotent 工具没有重复执行")
                .containsExactly("call_b");
    }

    @Test
    void withoutCheckpointTheSameKillReplaysTheInputAndOnlyTheNoteCarriesTheResults() {
        Recovered recovered = killDuringTheSecondModelCall(WorkerFixture.withoutCheckpoint());

        assertThat(recovered.result).isInstanceOf(TurnResult.Finished.class);
        assertThat(meter(recovered.nodeB, "meridian.turn.recoveries", "mode", "replay"))
                .isEqualTo(1.0);
        List<Msg> request = recovered.fixture.model.request(2).messages();
        assertThat(countUserText(request, USER_TEXT)).isEqualTo(1);
        assertThat(toolUseIds(request)).as("状态里没有本轮的内容，模型只能从恢复说明得知工具结果").isEmpty();
        assertThat(request.getLast().getTextContent())
                .contains(LOOKUP + "（call_a）：已成功执行", "结果摘要：" + LOOKUP + ":a", WRITE + "（call_b）：已成功执行");
        assertThat(recovered.fixture.writeCalls.toolUseIds()).containsExactly("call_b");
    }

    @Test
    void killDuringTheSecondBatchResumesFromTheFirstBatchCheckpointWithoutADanglingToolUse() throws Exception {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.toolCall("call_a", WRITE, Map.of("text", "第一批")))
                .respond(r -> r.toolCall("call_b", LOOKUP, Map.of("text", "第二批")))
                .respond(r -> r.text("续跑完成"))
                .build());
        WorkerNode nodeA = fixture.node("node-a");
        WorkerNode nodeB = fixture.node("node-b");
        TestSession session = fixture.session();
        fixture.lookupCalls.close();
        try {
            fixture.send(nodeA, session, USER_TEXT, Delivery.QUEUE);
            TurnExecution killed = nodeA.claim();
            killed.run().subscribe();
            fixture.lookupCalls.awaitEntered();
            killed.cancel("模拟进程被强杀");
            fixture.clock.advance(LEASE_EXPIRY);
            WorkerFixture.reap(nodeB);

            TurnResult result = nodeB.claimAndRun();

            assertThat(result).isInstanceOf(TurnResult.Finished.class);
            List<Msg> request = fixture.model.request(2).messages();
            assertThat(toolUseIds(request))
                    .as("第二批的 tool_use 在 checkpoint 之后才写入上下文")
                    .containsExactly("call_a");
            assertThat(toolResultIds(request)).containsExactly("call_a");
            assertThat(request.getLast().getTextContent()).contains(WRITE + "（call_a）：已成功执行", LOOKUP + "（call_b）：状态未知");
            assertThat(fixture.writeCalls.toolUseIds()).containsExactly("call_a");
        } finally {
            fixture.lookupCalls.open();
        }
    }

    @Test
    void checkpointCostsOneStateWritePerCompletedBatch() {
        long withCheckpoint = stateWritesOfATwoBatchTurn(WorkerProperties.defaults());
        long withoutCheckpoint = stateWritesOfATwoBatchTurn(WorkerFixture.withoutCheckpoint());

        assertThat(withoutCheckpoint).as("权限安装一次、轮次结束一次").isEqualTo(2);
        assertThat(withCheckpoint - withoutCheckpoint).as("两批工具各多一次保存").isEqualTo(2);
    }

    /** 第一次模型调用发起两个并行工具，第二次模型调用开始时强杀 node-a，node-b 回收并续跑。 */
    private static Recovered killDuringTheSecondModelCall(WorkerProperties properties) {
        AtomicReference<TurnExecution> running = new AtomicReference<>();
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.toolCall("call_a", LOOKUP, Map.of("text", "a"))
                        .toolCall("call_b", WRITE, Map.of("text", "b")))
                .respondTo((request, r) -> {
                    running.get().cancel("模拟进程被强杀");
                    r.text("这次回应不会被处理");
                })
                .respond(r -> r.text("续跑完成"))
                .build());
        WorkerNode nodeA = fixture.node("node-a", properties, s -> s);
        WorkerNode nodeB = fixture.node("node-b", properties, s -> s);
        TestSession session = fixture.session();
        fixture.send(nodeA, session, USER_TEXT, Delivery.QUEUE);
        TurnExecution killed = nodeA.claim();
        running.set(killed);
        TurnResult resultA = killed.run().block(WorkerNode.RUN_TIMEOUT);
        assertThat(resultA).isInstanceOf(TurnResult.Abandoned.class);

        fixture.clock.advance(LEASE_EXPIRY);
        WorkerFixture.reap(nodeB);
        TurnResult result = nodeB.claimAndRun();
        return new Recovered(fixture, nodeB, killed.turn().turnId(), result);
    }

    private static long stateWritesOfATwoBatchTurn(WorkerProperties properties) {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.toolCall("call_a", LOOKUP, Map.of("text", "a")))
                .respond(r -> r.toolCall("call_b", LOOKUP, Map.of("text", "b")))
                .respond(r -> r.text("完成"))
                .build());
        WorkerNode node = fixture.node("node-a", properties, s -> s);
        TestSession session = fixture.session();
        fixture.send(node, session, USER_TEXT, Delivery.QUEUE);
        assertThat(node.claimAndRun()).isInstanceOf(TurnResult.Finished.class);
        return DbAssertions.list(
                        "SELECT version FROM rt.agent_states WHERE thread_id = ? AND state_key = 'agent_state'",
                        Long.class,
                        session.mainThreadId())
                .getFirst();
    }

    private record Recovered(WorkerFixture fixture, WorkerNode nodeB, String turnId, TurnResult result) {}
}
