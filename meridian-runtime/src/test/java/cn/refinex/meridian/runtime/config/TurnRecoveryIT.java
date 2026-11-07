package cn.refinex.meridian.runtime.config;

import static cn.refinex.meridian.runtime.config.WorkerFixture.LOOKUP;
import static cn.refinex.meridian.runtime.config.WorkerFixture.WRITE;
import static cn.refinex.meridian.runtime.config.WorkerFixture.ofType;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.spi.engine.ThreadRef;
import cn.refinex.meridian.engine.spi.error.RateLimitTimeoutException;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.event.UserEvent.Delivery;
import cn.refinex.meridian.runtime.delivery.domain.InboxEntry;
import cn.refinex.meridian.runtime.dispatch.domain.RequeueReason;
import cn.refinex.meridian.runtime.dispatch.domain.TurnOutcome;
import cn.refinex.meridian.runtime.session.app.TurnExecution;
import cn.refinex.meridian.runtime.session.app.TurnFinisher;
import cn.refinex.meridian.runtime.session.app.TurnResult;
import cn.refinex.meridian.runtime.session.app.TurnWorkerLifecycle;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.shutdown.ShutdownState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * T06-A03、A06、A07、A08（M0 验证 P10）：四种终止方式下的恢复——进程被强杀、出错、用户中断、优雅下线，以及恢复时的人工确认与
 * 恢复次数用尽。两个节点（{@code node-a}、{@code node-b}）共享数据库、Redis、时钟与模型脚本，模型脚本按调用顺序跨节点消费。
 *
 * <p>强杀的模拟：执行停在工具中途时取消订阅（不运行任何收尾），工具在租约被接管之后才放行，所以它的结束记录写不进账本，与真实的
 * 进程被杀一致；时钟推进 31 秒，另一节点的回收器把轮次置回队列。steer 的重新投递通过 {@code InboxPort.consumeSteer} 让失败那次
 * 执行消费一条 steer 来验证（{@code SteerMiddleware} 在 T08 实现，届时重跑）。
 */
class TurnRecoveryIT {

    private static final String USER_TEXT = "查一下 a";
    private static final Duration LEASE_EXPIRY = Duration.ofSeconds(31);

    @AfterEach
    void resetGracefulShutdown() {
        GracefulShutdownManager.getInstance().resetForTesting();
    }

    @Test
    void killedProcessReplaysTheFullInputOnceAndRedeliversTheConsumedSteer() throws Exception {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.toolCall("call_1", LOOKUP, Map.of("text", "a")))
                .respond(r -> r.text("恢复后完成"))
                .respond(r -> r.text("处理了 steer"))
                .build());
        WorkerNode nodeA = fixture.node("node-a");
        WorkerNode nodeB = fixture.node("node-b");
        TestSession session = fixture.session();
        fixture.lookupCalls.close();
        try {
            fixture.send(nodeA, session, USER_TEXT, Delivery.QUEUE);
            TurnExecution killed = nodeA.claim();
            AtomicReference<TurnResult> resultA = new AtomicReference<>();
            killed.run().subscribe(resultA::set);
            fixture.lookupCalls.awaitEntered();
            String steer = consumeSteerAsTheRunningExecution(fixture, nodeA, session, killed, "顺便看看 b");

            killed.cancel("模拟进程被强杀");
            fixture.clock.advance(LEASE_EXPIRY);
            WorkerFixture.reap(nodeB);
            TurnResult resultB = nodeB.claimAndRun();

            String turnId = killed.turn().turnId();
            assertThat(resultA.get()).isInstanceOf(TurnResult.Abandoned.class);
            assertThat(resultB).isEqualTo(new TurnResult.Finished(turnId, TurnOutcome.COMPLETED, StopReason.END_TURN));
            List<Msg> replayed = fixture.model.request(1).messages();
            assertThat(countUserText(replayed, USER_TEXT))
                    .as("重放完整输入，用户消息只出现一次")
                    .isEqualTo(1);
            assertThat(replayed.getLast().getTextContent())
                    .startsWith("[平台恢复说明]")
                    .contains(LOOKUP + "（call_1）：状态未知");
            assertThat(DbAssertions.turn(turnId))
                    .containsEntry("recovery_attempt", 1)
                    .containsEntry("lease_epoch", 2L);
            assertThat(meter(nodeB, "meridian.turn.recoveries", "mode", "replay"))
                    .isEqualTo(1.0);

            // 被失败那次执行消费的 steer 已重新投递：本轮在注入前结束，它降级为 queue 成为下一轮
            TurnResult next = nodeB.claimAndRun();
            assertThat(next).isInstanceOf(TurnResult.Finished.class);
            assertThat(fixture.model.request(2).lastUserText()).isEqualTo("顺便看看 b");
            assertThat(DbAssertions.list(
                            "SELECT consumed_turn_id FROM rt.inbox_messages WHERE event_id = ?", String.class, steer))
                    .containsExactly(next.turnId());
            assertThat(countInState(session, USER_TEXT)).as("状态里的用户消息也只有一条").isEqualTo(1);
        } finally {
            fixture.lookupCalls.open();
        }
    }

    @Test
    void errorAfterAToolResumesFromTheSavedStateWithoutRerunningTheTool() {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.toolCall("call_w", WRITE, Map.of("text", "记录")))
                .failBeforeFirstChunk(new RateLimitTimeoutException("限流等待超时"))
                .respond(r -> r.text("写好了"))
                .build());
        // 关闭批次 checkpoint：只靠 AgentScope 出错时保存的中途状态恢复
        WorkerNode nodeA = fixture.node("node-a", WorkerFixture.withoutCheckpoint(), s -> s);
        WorkerNode nodeB = fixture.node("node-b", WorkerFixture.withoutCheckpoint(), s -> s);
        TestSession session = fixture.session();
        fixture.send(nodeA, session, USER_TEXT, Delivery.QUEUE);

        TurnResult resultA = nodeA.claimAndRun();
        TurnResult resultB = nodeB.claimAndRun();

        String turnId = resultA.turnId();
        assertThat(resultA).isEqualTo(new TurnResult.Requeued(turnId, RequeueReason.RETRYABLE_ERROR));
        assertThat(resultB).isEqualTo(new TurnResult.Finished(turnId, TurnOutcome.COMPLETED, StopReason.END_TURN));
        assertThat(fixture.writeCalls.toolUseIds())
                .as("non_idempotent 工具只执行了一次")
                .containsExactly("call_w");
        List<Msg> resumed = fixture.model.request(2).messages();
        assertThat(countUserText(resumed, USER_TEXT)).isEqualTo(1);
        assertThat(toolUseIds(resumed)).containsExactly("call_w");
        assertThat(toolResultIds(resumed))
                .as("保存的状态里 tool_use 与 tool_result 配对")
                .containsExactly("call_w");
        assertThat(resumed.getLast().getTextContent()).contains(WRITE + "（call_w）：已成功执行，不要重复执行。结果摘要：" + WRITE + ":记录");
        List<StoredEvent> events = fixture.events(nodeB, session);
        assertThat(ofType(events, EventType.SESSION_ERROR).getFirst().data()).contains("retryable");
        assertThat(ofType(events, EventType.SESSION_STATUS_RESCHEDULED)
                        .getFirst()
                        .data())
                .contains("retryable_error");
        assertThat(DbAssertions.turn(turnId)).containsEntry("recovery_attempt", 1);
        assertThat(meter(nodeB, "meridian.turn.recoveries", "mode", "recovery_only"))
                .isEqualTo(1.0);
    }

    @Test
    void userInterruptEndsTheTurnAsInterruptedAndLaterMessagesBecomeANewTurn() throws Exception {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.toolCall("call_1", LOOKUP, Map.of("text", "a")))
                .respond(r -> r.text("收到后续消息"))
                .build());
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        fixture.lookupCalls.close();
        try {
            fixture.send(node, session, USER_TEXT, Delivery.QUEUE);
            TurnExecution execution = node.claim();
            CompletableFuture<TurnResult> running = execution.run().toFuture();
            fixture.lookupCalls.awaitEntered();
            fixture.send(node, session, "中断之后再问一句", Delivery.QUEUE);

            // 数据库字段为准：续租读到中断请求，Worker 调用 AgentEngine.interrupt（T08 再加节点频道加速）
            DbAssertions.update(
                    "UPDATE rt.turns SET interrupt_requested_at = ? WHERE id = ?",
                    Timestamp.from(fixture.clock.instant()),
                    execution.turn().turnId());
            node.node().renewer().renewAll();
            fixture.lookupCalls.open();

            TurnResult interrupted = running.get(30, TimeUnit.SECONDS);
            assertThat(interrupted)
                    .isEqualTo(new TurnResult.Finished(
                            execution.turn().turnId(), TurnOutcome.INTERRUPTED, StopReason.INTERRUPTED));
            assertThat(DbAssertions.turn(execution.turn().turnId()))
                    .containsEntry("status", "interrupted")
                    .containsEntry("stop_reason", "interrupted");
            assertThat(fixture.model.requests()).as("中断在下一次推理之前生效").hasSize(1);

            TurnResult next = node.claimAndRun();
            assertThat(next).isInstanceOf(TurnResult.Finished.class);
            assertThat(fixture.model.request(1).lastUserText()).isEqualTo("中断之后再问一句");
            assertThat(countUserText(fixture.model.request(1).messages(), USER_TEXT))
                    .isEqualTo(1);
        } finally {
            fixture.lookupCalls.open();
        }
    }

    @Test
    void gracefulShutdownRequeuesWithoutCountingARecoveryAndAnotherNodeResumes() throws Exception {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.toolCall("call_1", LOOKUP, Map.of("text", "a")))
                .respond(r -> r.text("另一节点续跑完成"))
                .build());
        WorkerNode nodeA = fixture.node("node-a");
        WorkerNode nodeB = fixture.node("node-b");
        TestSession session = fixture.session();
        TurnWorkerLifecycle lifecycleA = nodeA.lifecycle();
        fixture.lookupCalls.close();
        try {
            fixture.send(nodeA, session, USER_TEXT, Delivery.QUEUE);
            lifecycleA.start();
            fixture.lookupCalls.awaitEntered();
            String turnId = DbAssertions.list(
                            "SELECT id FROM rt.turns WHERE session_id = ?", String.class, session.sessionId())
                    .getFirst();

            CompletableFuture<Void> stopping = CompletableFuture.runAsync(lifecycleA::stop);
            WorkerFixture.await(
                    () -> GracefulShutdownManager.getInstance().getState(), state -> state != ShutdownState.RUNNING);
            fixture.lookupCalls.open();
            stopping.get(90, TimeUnit.SECONDS);

            assertThat(lifecycleA.isRunning()).isFalse();
            assertThat(lifecycleA.runningCount()).isZero();
            assertThat(DbAssertions.turn(turnId))
                    .containsEntry("status", "queued")
                    .containsEntry("recovery_attempt", 0)
                    .containsEntry("lease_owner", "node-a");
            assertThat(DbAssertions.running(session.tenantId())).isZero();
            assertThat(nodeA.node().registry().heldThreadIds()).isEmpty();
            List<StoredEvent> events = fixture.events(nodeA, session);
            assertThat(ofType(events, EventType.SESSION_STATUS_RESCHEDULED))
                    .singleElement()
                    .satisfies(e -> assertThat(e.data()).contains("node_shutdown"));

            GracefulShutdownManager.getInstance().resetForTesting();
            TurnResult resumed = nodeB.claimAndRun();

            assertThat(resumed).isEqualTo(new TurnResult.Finished(turnId, TurnOutcome.COMPLETED, StopReason.END_TURN));
            assertThat(fixture.lookupCalls.toolUseIds()).as("下线前已完成的工具不再执行").containsExactly("call_1");
            List<Msg> request = fixture.model.request(1).messages();
            assertThat(countUserText(request, USER_TEXT)).isEqualTo(1);
            assertThat(toolResultIds(request)).containsExactly("call_1");
            assertThat(DbAssertions.turn(turnId))
                    .containsEntry("recovery_attempt", 0)
                    .containsEntry("lease_epoch", 2L);
        } finally {
            fixture.lookupCalls.open();
        }
    }

    @Test
    void unknownNonIdempotentCallCreatesARecoveryConfirmationAndTheSessionRequiresAction() throws Exception {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.toolCall("call_w", WRITE, Map.of("text", "扣款")))
                .build());
        WorkerNode nodeA = fixture.node("node-a");
        WorkerNode nodeB = fixture.node("node-b");
        TestSession session = fixture.session();
        fixture.writeCalls.close();
        try {
            fixture.send(nodeA, session, "扣一笔款", Delivery.QUEUE);
            TurnExecution killed = nodeA.claim();
            killed.run().subscribe();
            fixture.writeCalls.awaitEntered();
            killed.cancel("模拟进程被强杀");
            fixture.clock.advance(LEASE_EXPIRY);
            WorkerFixture.reap(nodeB);
            Instant reclaimedAt = fixture.clock.instant();

            TurnResult result = nodeB.claimAndRun();

            String turnId = killed.turn().turnId();
            assertThat(result).isEqualTo(new TurnResult.Finished(turnId, TurnOutcome.AWAITING_ACTION, null));
            assertThat(fixture.model.requests()).as("恢复轮没有执行").hasSize(1);
            assertThat(fixture.writeCalls.toolUseIds()).containsExactly("call_w");
            Map<String, Object> action = DbAssertions.row(
                    "SELECT type, status, payload::text AS payload, expires_at FROM rt.pending_actions WHERE turn_id = ?",
                    turnId);
            assertThat(action).containsEntry("type", "recovery_confirmation").containsEntry("status", "pending");
            assertThat((String) action.get("payload"))
                    .contains(
                            "\"tool_use_id\": \"call_w\"",
                            "\"idempotency_key\": \"idem-call_w\"",
                            "\"recovery_mode\": \"replay_input\"");
            assertThat(DbAssertions.instant(action.get("expires_at")))
                    .isEqualTo(reclaimedAt.plus(Duration.ofMinutes(30)));
            assertThat(DbAssertions.turn(turnId)).containsEntry("status", "awaiting_action");
            assertThat(DbAssertions.list(
                            "SELECT status FROM rt.sessions WHERE id = ?", String.class, session.sessionId()))
                    .containsExactly("requires_action");
            StoredEvent last = fixture.events(nodeB, session).getLast();
            assertThat(last.type()).isEqualTo(EventType.SESSION_STATUS_REQUIRES_ACTION);
            assertThat(last.data()).contains("recovery_confirmation", "call_w", "expires_at");
            assertThat(DbAssertions.running(session.tenantId())).isZero();
        } finally {
            fixture.writeCalls.open();
        }
    }

    @Test
    void reachingTwoRecoveryAttemptsEndsTheTurnWithError() throws Exception {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.toolCall("call_1", LOOKUP, Map.of("text", "a")))
                .build());
        WorkerNode nodeA = fixture.node("node-a");
        WorkerNode nodeB = fixture.node("node-b");
        TestSession session = fixture.session();
        fixture.lookupCalls.close();
        try {
            fixture.send(nodeA, session, USER_TEXT, Delivery.QUEUE);
            TurnExecution first = nodeA.claim();
            first.run().subscribe();
            fixture.lookupCalls.awaitEntered();
            first.cancel("模拟进程被强杀");
            fixture.clock.advance(LEASE_EXPIRY);
            WorkerFixture.reap(nodeB);
            // node-b 认领后还没执行就崩溃
            TurnExecution second = nodeB.claim();
            second.cancel("模拟进程被强杀");
            fixture.clock.advance(LEASE_EXPIRY);
            WorkerFixture.reap(nodeA);

            TurnResult result = nodeA.claimAndRun();

            String turnId = first.turn().turnId();
            assertThat(result).isEqualTo(new TurnResult.Finished(turnId, TurnOutcome.FAILED, StopReason.ERROR));
            assertThat(DbAssertions.turn(turnId))
                    .containsEntry("status", "failed")
                    .containsEntry("stop_reason", "error")
                    .containsEntry("recovery_attempt", 2);
            List<StoredEvent> events = fixture.events(nodeA, session);
            assertThat(events.get(events.size() - 2).data())
                    .contains(TurnFinisher.RECOVERY_EXHAUSTED_CODE, "exhausted");
            assertThat(events.getLast().type()).isEqualTo(EventType.SESSION_STATUS_IDLE);
            assertThat(events.getLast().data()).contains("error");
            assertThat(fixture.model.requests()).hasSize(1);
        } finally {
            fixture.lookupCalls.open();
        }
    }

    /** 让正在执行的那次执行像 {@code SteerMiddleware} 一样消费一条 steer 消息，返回它的事件 ID。 */
    private static String consumeSteerAsTheRunningExecution(
            WorkerFixture fixture, WorkerNode node, TestSession session, TurnExecution execution, String text) {
        String eventId = fixture.appendUserMessage(node, session, text, Delivery.STEER);
        PlatformContexts.run(
                session.context(),
                () -> node.inbox()
                        .add(new InboxEntry(session.sessionId(), session.mainThreadId(), eventId, Delivery.STEER)));
        List<?> consumed = node.inbox()
                .consumeSteer(
                        new ThreadRef(session.sessionId(), session.mainThreadId()),
                        execution.turn().turnId(),
                        execution.turn().epoch())
                .contextWrite(PlatformContexts.write(session.context()))
                .block(Duration.ofSeconds(10));
        assertThat(consumed).hasSize(1);
        assertThat(DbAssertions.list(
                        "SELECT consumed_epoch FROM rt.inbox_messages WHERE event_id = ?", Long.class, eventId))
                .containsExactly(execution.turn().epoch());
        return eventId;
    }

    static long countUserText(List<Msg> messages, String text) {
        return messages.stream()
                .filter(m -> m.getRole() == MsgRole.USER)
                .filter(m -> text.equals(m.getTextContent()))
                .count();
    }

    static List<String> toolUseIds(List<Msg> messages) {
        return blocks(messages, ToolUseBlock.class).stream()
                .map(ToolUseBlock::getId)
                .toList();
    }

    static List<String> toolResultIds(List<Msg> messages) {
        return blocks(messages, ToolResultBlock.class).stream()
                .map(ToolResultBlock::getId)
                .toList();
    }

    private static <T extends ContentBlock> List<T> blocks(List<Msg> messages, Class<T> type) {
        return messages.stream()
                .flatMap(m -> m.getContent().stream())
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    private static long countInState(TestSession session, String text) {
        String state = DbAssertions.list(
                        "SELECT state::text FROM rt.agent_states WHERE thread_id = ? AND state_key = 'agent_state'",
                        String.class,
                        session.mainThreadId())
                .getFirst();
        return state.split(Pattern.quote(text), -1).length - 1L;
    }

    static double meter(WorkerNode node, String name, String tagKey, String tagValue) {
        MeterRegistry meters = node.node().meters();
        Counter counter = meters.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0 : counter.count();
    }
}
