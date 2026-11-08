package cn.refinex.meridian.runtime.config;

import static cn.refinex.meridian.runtime.config.WorkerFixture.LOOKUP;
import static cn.refinex.meridian.runtime.config.WorkerFixture.message;
import static cn.refinex.meridian.runtime.config.WorkerFixture.ofType;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.event.UserEvent.Delivery;
import cn.refinex.meridian.runtime.dispatch.app.DispatchProperties;
import cn.refinex.meridian.runtime.session.app.TurnExecution;
import cn.refinex.meridian.runtime.session.app.TurnResult;
import cn.refinex.meridian.runtime.session.app.WorkerProperties;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import io.agentscope.core.message.Msg;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * T08-A10：用 {@code SteerMiddleware} 重跑 P10 的强杀场景（架构设计「崩溃恢复」，ADR-0028 第 1 条）。steer 在第一批工具之后的
 * 推理前被真实注入（记录失败那次执行的 {@code consumed_epoch}），随后第二批工具执行到一半时进程被强杀：
 *
 * <ul>
 *   <li>没有批次 checkpoint：本轮没有内容写入，重放完整输入；被失败那次执行消费的 steer 重新投递，恢复的执行再次注入，不丢失、不重复。
 *   <li>开启批次 checkpoint：steer 在注入后、同一次推理前进入 checkpoint（{@code SteerMiddleware} 排在批次 checkpoint 之外），
 *       恢复走 {@code RecoveryOnly}，steer 已在状态中，不重新投递。
 * </ul>
 */
class SteerRecoveryIT {

    private static final Duration LEASE_EXPIRY =
            DispatchProperties.defaults().leaseTtl().plusSeconds(1);

    private static final String USER_TEXT = "查订单 a 和 b";

    private static final String STEER_TEXT = "b 查完顺便看看 c";

    @AfterEach
    void resetGracefulShutdown() {
        GracefulShutdownManager.getInstance().resetForTesting();
    }

    @Test
    void steerConsumedByAKilledExecutionIsRedeliveredAndInjectedAgainWithoutCheckpoint() throws Exception {
        WorkerFixture fixture = new WorkerFixture(script());
        WorkerNode nodeA = fixture.node("node-a", WorkerFixture.withoutCheckpoint(), states -> states);
        WorkerNode nodeB = fixture.node("node-b", WorkerFixture.withoutCheckpoint(), states -> states);
        TestSession session = fixture.session();

        Killed killed = killDuringTheSecondBatch(fixture, nodeA, nodeB, session);

        assertThat(steerRow(killed.steerEventId()))
                .as("失败那次执行在第二次推理前消费了 steer")
                .containsEntry("consumed_turn_id", killed.turnId())
                .containsEntry("consumed_epoch", 1L);
        TurnResult result = nodeB.claimAndRun();

        assertThat(result)
                .extracting(r -> ((TurnResult.Finished) r).stopReason())
                .isEqualTo(StopReason.END_TURN);
        assertThat(TurnRecoveryIT.meter(nodeB, "meridian.turn.recoveries", "mode", "replay"))
                .isEqualTo(1.0);
        List<Msg> recovered = fixture.model.request(2).messages();
        assertThat(count(recovered, USER_TEXT)).as("重放的用户消息只有一条").isEqualTo(1);
        assertThat(count(recovered, STEER_TEXT)).as("重新投递的 steer 再次注入，只有一条").isEqualTo(1);
        assertThat(steerRow(killed.steerEventId()))
                .as("由恢复的这次执行（epoch 2）重新消费")
                .containsEntry("consumed_turn_id", killed.turnId())
                .containsEntry("consumed_epoch", 2L);
        assertThat(countInState(session, STEER_TEXT)).isEqualTo(1);
        assertThat(countInState(session, USER_TEXT)).isEqualTo(1);
        assertThat(ofType(fixture.events(nodeB, session), EventType.SESSION_MESSAGE_CONSUMED))
                .filteredOn(e -> e.data().contains(killed.steerEventId()))
                .as("两次执行各写一次消费事件")
                .hasSize(2);
        assertThat(DbAssertions.list(
                        "SELECT count(*) FROM rt.turns WHERE thread_id = ?", Long.class, session.mainThreadId()))
                .containsExactly(1L);
    }

    @Test
    void steerInjectedBeforeACheckpointSurvivesTheKillInTheSavedState() throws Exception {
        WorkerFixture fixture = new WorkerFixture(script());
        WorkerNode nodeA = fixture.node("node-a", WorkerProperties.defaults(), states -> states);
        WorkerNode nodeB = fixture.node("node-b", WorkerProperties.defaults(), states -> states);
        TestSession session = fixture.session();

        Killed killed = killDuringTheSecondBatch(fixture, nodeA, nodeB, session);
        TurnResult result = nodeB.claimAndRun();

        assertThat(result)
                .extracting(r -> ((TurnResult.Finished) r).stopReason())
                .isEqualTo(StopReason.END_TURN);
        assertThat(TurnRecoveryIT.meter(nodeB, "meridian.turn.recoveries", "mode", "recovery_only"))
                .isEqualTo(1.0);
        List<Msg> recovered = fixture.model.request(2).messages();
        assertThat(count(recovered, STEER_TEXT))
                .as("steer 在 checkpoint 里，恢复后只出现一次")
                .isEqualTo(1);
        assertThat(steerRow(killed.steerEventId())).as("状态中已有，不重新投递").containsEntry("consumed_epoch", 1L);
        assertThat(countInState(session, STEER_TEXT)).isEqualTo(1);
    }

    /** 第一次推理：查 a；第二次推理（注入 steer 之后）：查 b；恢复后：直接回答。 */
    private static ScriptedModel script() {
        return ScriptedModel.builder()
                .respond(r -> r.toolCall("call_a", LOOKUP, Map.of("text", "a")))
                .respond(r -> r.toolCall("call_b", LOOKUP, Map.of("text", "b")))
                .respond(r -> r.text("a、b、c 都看过了"))
                .build();
    }

    /**
     * 第一批工具执行中途发来 steer；放行第一批，steer 在第二次推理前注入；第二批工具执行到一半时强杀节点 A，时钟推进到租约过期，
     * 节点 B 回收。
     */
    private static Killed killDuringTheSecondBatch(
            WorkerFixture fixture, WorkerNode nodeA, WorkerNode nodeB, TestSession session) throws Exception {
        fixture.lookupCalls.close();
        try {
            fixture.post(nodeA, session, message(USER_TEXT, Delivery.QUEUE));
            TurnExecution execution = nodeA.claim();
            execution.run().subscribe();
            fixture.lookupCalls.awaitEntered();
            String steer = fixture.post(nodeA, session, message(STEER_TEXT, Delivery.STEER))
                    .getFirst()
                    .id();
            fixture.lookupCalls.close();
            fixture.lookupCalls.openOldest();
            fixture.lookupCalls.awaitEntered();
            assertThat(fixture.model.request(1).lastUserText())
                    .as("第二次推理前注入了 steer")
                    .isEqualTo(STEER_TEXT);

            execution.cancel("模拟进程被强杀");
            fixture.clock.advance(LEASE_EXPIRY);
            WorkerFixture.reap(nodeB);
            return new Killed(execution.turn().turnId(), steer);
        } finally {
            fixture.lookupCalls.open();
        }
    }

    private static Map<String, Object> steerRow(String eventId) {
        return DbAssertions.row(
                "SELECT consumed_turn_id, consumed_epoch FROM rt.inbox_messages WHERE event_id = ?", eventId);
    }

    private static long count(List<Msg> messages, String text) {
        return messages.stream()
                .filter(m -> m.getTextContent() != null && m.getTextContent().equals(text))
                .count();
    }

    private static long countInState(TestSession session, String text) {
        String state = DbAssertions.list(
                        "SELECT state::text FROM rt.agent_states WHERE thread_id = ? AND state_key = 'agent_state'",
                        String.class,
                        session.mainThreadId())
                .getFirst();
        return state.split(Pattern.quote(text), -1).length - 1L;
    }

    /** 被强杀的执行。 */
    private record Killed(String turnId, String steerEventId) {}
}
