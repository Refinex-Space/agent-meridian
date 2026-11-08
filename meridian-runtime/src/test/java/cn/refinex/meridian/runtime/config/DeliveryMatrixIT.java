package cn.refinex.meridian.runtime.config;

import static cn.refinex.meridian.runtime.config.WorkerFixture.LOOKUP;
import static cn.refinex.meridian.runtime.config.WorkerFixture.WRITE;
import static cn.refinex.meridian.runtime.config.WorkerFixture.message;
import static cn.refinex.meridian.runtime.config.WorkerFixture.ofType;
import static cn.refinex.meridian.runtime.config.WorkerFixture.types;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.event.UserEvent;
import cn.refinex.meridian.engine.spi.event.UserEvent.Delivery;
import cn.refinex.meridian.engine.spi.event.UserEvent.RememberScope;
import cn.refinex.meridian.runtime.approval.domain.PendingActionStatus;
import cn.refinex.meridian.runtime.approval.domain.StoredPendingAction;
import cn.refinex.meridian.runtime.dispatch.app.DispatchProperties;
import cn.refinex.meridian.runtime.dispatch.domain.TurnOutcome;
import cn.refinex.meridian.runtime.session.app.TurnExecution;
import cn.refinex.meridian.runtime.session.app.TurnResult;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.stream.infra.EngineJson;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * T08-A01、A02、A03：架构设计「消息投递」表的每个格子（投递方式 × 线程状态），经与 API 相同的发送事件用例（{@code EventIntake}）
 * 投递，Worker 用真实装配的内核（{@code ScriptedModel}）执行。测试名写明"投递方式_线程状态"：
 *
 * <pre>
 *   投递方式 \ 状态 | 空闲                     | 运行中                                           | 等待回应
 *   queue          | queue_idle_*             | queue_running_*                                  | queue_awaitingAction_*
 *   steer          | steer_idle_*             | steer_running_injected*、steer_running_turnEnds* | steer_awaitingAction_*
 *   user.interrupt | interrupt_idle_*         | interrupt_running_*（频道、续租兜底）、interrupt_queued_* | interrupt_awaitingAction_*
 *   删除会话       | —                        | delete_running_*                                 | delete_awaitingAction_*
 * </pre>
 *
 * 运行中靠记账工具在执行中途卡住（{@code lookupCalls.close()}）制造；等待回应靠没有放行规则的写入工具（DEFAULT 模式默认 ask）。
 */
class DeliveryMatrixIT {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @AfterEach
    void resetGracefulShutdown() {
        GracefulShutdownManager.getInstance().resetForTesting();
    }

    // ------------------------------------------------------------------ queue

    @Test
    void queue_idle_startsAMessagesTurnRightAway() {
        WorkerFixture fixture = new WorkerFixture(
                ScriptedModel.builder().respond(r -> r.text("好的")).build());
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();

        fixture.post(node, session, message("你好", Delivery.QUEUE));

        assertThat(WorkerFixture.latestTurn(session))
                .containsEntry("kind", "messages")
                .containsEntry("status", "queued");
        assertThat(inboxRows(session)).isEmpty();
        assertThat(node.claimAndRun()).isInstanceOf(TurnResult.Finished.class);
        assertThat(sessionStatus(session)).isEqualTo("idle");
    }

    @Test
    void queue_running_mergesIntoTheNextTurnWhenThisTurnEnds() throws Exception {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.toolCall("call_1", LOOKUP, Map.of("text", "订单 42")))
                .respond(r -> r.text("第一轮完成"))
                .respondTo((request, r) -> r.text("第二轮收到：" + request.lastUserText()))
                .build());
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        fixture.post(node, session, message("查订单 42", Delivery.QUEUE));

        fixture.lookupCalls.close();
        TurnExecution first = node.claim();
        CompletableFuture<TurnResult> running = first.run().toFuture();
        try {
            fixture.lookupCalls.awaitEntered();
            List<StoredEvent> posted = fixture.post(node, session, message("再查订单 43", Delivery.QUEUE));
            assertThat(inboxRows(session)).singleElement().satisfies(row -> {
                assertThat(row).containsEntry("delivery", "queue").containsEntry("status", "pending");
                assertThat(row.get("event_id")).isEqualTo(posted.getFirst().id());
            });
        } finally {
            fixture.lookupCalls.open();
        }
        assertThat(running.get(30, TimeUnit.SECONDS)).isInstanceOf(TurnResult.Finished.class);

        Map<String, Object> next = WorkerFixture.latestTurn(session);
        assertThat(next).containsEntry("kind", "messages").containsEntry("status", "queued");
        assertThat(node.claimAndRun()).isInstanceOf(TurnResult.Finished.class);
        assertThat(fixture.model.request(2).lastUserText()).isEqualTo("再查订单 43");
        assertThat(inboxRows(session))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("status", "consumed")
                        .containsEntry("consumed_turn_id", next.get("id")));
    }

    @Test
    void queue_awaitingAction_staysInTheInboxUntilTheResumptionTurnEnds() {
        WorkerFixture fixture = new WorkerFixture(
                ScriptedModel.builder()
                        .respond(r -> r.toolCall("call_w", WRITE, Map.of("text", "新记录")))
                        .respond(r -> r.text("已写入"))
                        .respondTo((request, r) -> r.text("收到：" + request.lastUserText()))
                        .build(),
                WorkerFixture::lookupOnlyAllowed);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        fixture.post(node, session, message("写一条记录", Delivery.QUEUE));
        assertThat(node.claimAndRun())
                .isEqualTo(new TurnResult.Finished(turnId(session), TurnOutcome.AWAITING_ACTION, null));

        fixture.post(node, session, message("写完告诉我", Delivery.QUEUE));
        assertThat(WorkerFixture.latestTurn(session)).containsEntry("status", "awaiting_action");
        assertThat(inboxRows(session))
                .singleElement()
                .satisfies(row ->
                        assertThat(row).containsEntry("delivery", "queue").containsEntry("status", "pending"));

        StoredPendingAction action = fixture.actions(session).getFirst();
        fixture.post(node, session, approve(action));
        assertThat(WorkerFixture.latestTurn(session)).containsEntry("kind", "confirmations");
        assertThat(inboxRows(session).getFirst()).containsEntry("status", "pending");

        assertThat(node.claimAndRun()).isInstanceOf(TurnResult.Finished.class);
        assertThat(WorkerFixture.latestTurn(session))
                .containsEntry("kind", "messages")
                .containsEntry("status", "queued");
        assertThat(node.claimAndRun()).isInstanceOf(TurnResult.Finished.class);
        assertThat(fixture.model.request(2).lastUserText()).isEqualTo("写完告诉我");
    }

    // ------------------------------------------------------------------ steer

    @Test
    void steer_idle_behavesLikeQueue() {
        WorkerFixture fixture = new WorkerFixture(
                ScriptedModel.builder().respond(r -> r.text("好的")).build());
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();

        fixture.post(node, session, message("你好", Delivery.STEER));

        assertThat(WorkerFixture.latestTurn(session))
                .containsEntry("kind", "messages")
                .containsEntry("status", "queued");
        assertThat(inboxRows(session)).isEmpty();
        assertThat(node.claimAndRun()).isInstanceOf(TurnResult.Finished.class);
    }

    @Test
    void steer_running_isInjectedBeforeTheNextReasoningAndRecordsTheConsumingEpoch() throws Exception {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.toolCall("call_1", LOOKUP, Map.of("text", "订单 42")))
                .respondTo((request, r) -> r.text("按新要求处理：" + request.lastUserText()))
                .build());
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        fixture.post(node, session, message("查订单 42", Delivery.QUEUE));

        fixture.lookupCalls.close();
        TurnExecution execution = node.claim();
        CompletableFuture<TurnResult> running = execution.run().toFuture();
        String steerEventId;
        try {
            fixture.lookupCalls.awaitEntered();
            steerEventId = fixture.post(node, session, message("改查订单 43", Delivery.STEER))
                    .getFirst()
                    .id();
        } finally {
            fixture.lookupCalls.open();
        }

        assertThat(running.get(30, TimeUnit.SECONDS))
                .isEqualTo(
                        new TurnResult.Finished(execution.turn().turnId(), TurnOutcome.COMPLETED, StopReason.END_TURN));
        assertThat(fixture.model.request(1).lastUserText())
                .as("第二次推理（工具结果之后）的输入里有 steer 消息")
                .isEqualTo("改查订单 43");
        assertThat(inboxRows(session))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("delivery", "steer")
                        .containsEntry("status", "consumed")
                        .containsEntry("consumed_turn_id", execution.turn().turnId())
                        .containsEntry("consumed_epoch", execution.turn().epoch()));
        List<StoredEvent> consumed = ofType(fixture.events(node, session), EventType.SESSION_MESSAGE_CONSUMED);
        assertThat(consumed).hasSize(2);
        assertThat(((SessionEvent.MessageConsumed) EngineJson.readEvent(
                                EventType.SESSION_MESSAGE_CONSUMED,
                                consumed.getLast().data()))
                        .eventIds())
                .containsExactly(steerEventId);
        assertThat(consumed.getLast().turnId()).isEqualTo(execution.turn().turnId());
        assertThat(DbAssertions.row("SELECT count(*) AS n FROM rt.turns WHERE thread_id = ?", session.mainThreadId()))
                .as("steer 已注入当前轮，不再生成下一轮")
                .containsEntry("n", 1L);
    }

    @Test
    void steer_running_turnEndsBeforeInjection_isDowngradedToQueue() throws Exception {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.toolCall("call_1", LOOKUP, Map.of("text", "订单 42")))
                .respondTo((request, r) -> r.text("下一轮收到：" + request.lastUserText()))
                .build());
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        fixture.post(node, session, message("查订单 42", Delivery.QUEUE));

        fixture.lookupCalls.close();
        TurnExecution execution = node.claim();
        CompletableFuture<TurnResult> running = execution.run().toFuture();
        try {
            fixture.lookupCalls.awaitEntered();
            fixture.post(node, session, message("改查订单 43", Delivery.STEER));
            // 中断让本轮在下一次推理前结束，steer 来不及注入
            fixture.post(node, session, new UserEvent.Interrupt());
            node.node().renewer().renewAll();
        } finally {
            fixture.lookupCalls.open();
        }

        assertThat(running.get(30, TimeUnit.SECONDS))
                .isEqualTo(new TurnResult.Finished(
                        execution.turn().turnId(), TurnOutcome.INTERRUPTED, StopReason.INTERRUPTED));
        Map<String, Object> next = WorkerFixture.latestTurn(session);
        assertThat(next).containsEntry("kind", "messages").containsEntry("status", "queued");
        assertThat(inboxRows(session))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("delivery", "steer")
                        .containsEntry("consumed_turn_id", next.get("id"))
                        .containsEntry("consumed_epoch", null));
        assertThat(node.claimAndRun()).isInstanceOf(TurnResult.Finished.class);
        assertThat(fixture.model.request(1).lastUserText()).isEqualTo("改查订单 43");
    }

    @Test
    void steer_awaitingAction_isDowngradedToQueue() {
        WorkerFixture fixture = new WorkerFixture(
                ScriptedModel.builder()
                        .respond(r -> r.toolCall("call_w", WRITE, Map.of("text", "新记录")))
                        .build(),
                WorkerFixture::lookupOnlyAllowed);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        fixture.post(node, session, message("写一条记录", Delivery.QUEUE));
        node.claimAndRun();

        fixture.post(node, session, message("顺便检查格式", Delivery.STEER));

        assertThat(inboxRows(session))
                .singleElement()
                .satisfies(row ->
                        assertThat(row).containsEntry("delivery", "queue").containsEntry("status", "pending"));
    }

    // ------------------------------------------------------------------ user.interrupt

    @Test
    void interrupt_idle_hasNoEffect() {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder().build());
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();

        List<StoredEvent> stored = fixture.post(node, session, new UserEvent.Interrupt());

        assertThat(stored).extracting(e -> e.type().value()).containsExactly("user.interrupt");
        assertThat(DbAssertions.rows("SELECT id FROM rt.turns WHERE thread_id = ?", session.mainThreadId()))
                .isEmpty();
        assertThat(sessionStatus(session)).isEqualTo("idle");
    }

    @Test
    void interrupt_running_takesEffectAtTheNextCheckpointThroughTheNodeChannel() throws Exception {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.toolCall("call_1", LOOKUP, Map.of("text", "订单 42")))
                .respond(r -> r.toolCall("call_2", WRITE, Map.of("text", "不应执行")))
                .build());
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        fixture.post(node, session, message("查订单 42", Delivery.QUEUE));
        node.channelListener().start();
        try {
            fixture.lookupCalls.close();
            TurnExecution execution = node.claim();
            CompletableFuture<TurnResult> running = execution.run().toFuture();
            long sent;
            try {
                fixture.lookupCalls.awaitEntered();
                sent = System.nanoTime();
                fixture.post(node, session, new UserEvent.Interrupt());
                assertThat(WorkerFixture.latestTurn(session).get("interrupt_requested_at"))
                        .isNotNull();
            } finally {
                fixture.lookupCalls.open();
            }

            // 没有续租任务在跑：中断只能经 node:{lease_owner} 频道送达；最迟在模型调用之后的工具执行检查点生效
            assertThat(running.get(30, TimeUnit.SECONDS))
                    .isEqualTo(new TurnResult.Finished(
                            execution.turn().turnId(), TurnOutcome.INTERRUPTED, StopReason.INTERRUPTED));
            assertThat(Duration.ofNanos(System.nanoTime() - sent)).isLessThan(Duration.ofSeconds(10));
            assertThat(fixture.writeCalls.toolUseIds()).isEmpty();
            assertThat(WorkerFixture.latestTurn(session))
                    .containsEntry("status", "interrupted")
                    .containsEntry("stop_reason", "interrupted");
        } finally {
            node.channelListener().stop();
        }
    }

    @Test
    void interrupt_running_whenTheNotificationIsLost_isPickedUpByTheNextRenewal() throws Exception {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.toolCall("call_1", LOOKUP, Map.of("text", "订单 42")))
                .respond(r -> r.toolCall("call_2", WRITE, Map.of("text", "不应执行")))
                .build());
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        fixture.post(node, session, message("查订单 42", Delivery.QUEUE));

        fixture.lookupCalls.close();
        TurnExecution execution = node.claim();
        CompletableFuture<TurnResult> running = execution.run().toFuture();
        try {
            fixture.lookupCalls.awaitEntered();
            // 节点没有订阅自己的频道，通知丢失；续租周期到了才发现中断请求
            fixture.post(node, session, new UserEvent.Interrupt());
            fixture.clock.advance(DispatchProperties.defaults().renewInterval());
            node.node().renewer().renewAll();
        } finally {
            fixture.lookupCalls.open();
        }

        assertThat(DispatchProperties.defaults().renewInterval()).isLessThanOrEqualTo(Duration.ofSeconds(10));
        assertThat(running.get(30, TimeUnit.SECONDS))
                .isEqualTo(new TurnResult.Finished(
                        execution.turn().turnId(), TurnOutcome.INTERRUPTED, StopReason.INTERRUPTED));
        assertThat(fixture.writeCalls.toolUseIds()).isEmpty();
    }

    @Test
    void interrupt_queued_cancelsTheTurnAndLaterMessagesFormTheNextTurn() {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respondTo((request, r) -> r.text("收到：" + request.lastUserText()))
                .build());
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        fixture.post(node, session, message("先查订单 42", Delivery.QUEUE));
        String queuedTurn = turnId(session);

        fixture.post(node, session, new UserEvent.Interrupt(), message("算了，查订单 43", Delivery.STEER));

        assertThat(DbAssertions.row("SELECT status, stop_reason FROM rt.turns WHERE id = ?", queuedTurn))
                .containsEntry("status", "cancelled")
                .containsEntry("stop_reason", "interrupted");
        List<StoredEvent> events = fixture.events(node, session);
        assertThat(types(events)).containsSubsequence("user.interrupt", "user.message", "session.status_idle");
        assertThat(events.getLast().turnId()).isEqualTo(queuedTurn);
        Map<String, Object> next = WorkerFixture.latestTurn(session);
        assertThat(next.get("id")).isNotEqualTo(queuedTurn);
        assertThat(next).containsEntry("kind", "messages").containsEntry("status", "queued");

        assertThat(node.claimAndRun()).isInstanceOf(TurnResult.Finished.class);
        assertThat(fixture.model.requests())
                .singleElement()
                .satisfies(request -> assertThat(request.lastUserText()).isEqualTo("算了，查订单 43"));
    }

    @Test
    void interrupt_awaitingAction_deniesEveryActionAndRunsOneResumptionTurnThenStops() {
        WorkerFixture fixture = new WorkerFixture(
                ScriptedModel.builder()
                        .respond(r -> r.toolCall("call_w", WRITE, Map.of("text", "新记录")))
                        .build(),
                WorkerFixture::lookupOnlyAllowed);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        fixture.post(node, session, message("写一条记录", Delivery.QUEUE));
        node.claimAndRun();
        String awaitingTurn = turnId(session);

        fixture.post(node, session, new UserEvent.Interrupt());

        assertThat(fixture.actions(session))
                .singleElement()
                .satisfies(action -> assertThat(action.status()).isEqualTo(PendingActionStatus.RESOLVED));
        assertThat(DbAssertions.row("SELECT status FROM rt.turns WHERE id = ?", awaitingTurn))
                .containsEntry("status", "completed");
        Map<String, Object> resumption = WorkerFixture.latestTurn(session);
        assertThat(resumption).containsEntry("kind", "confirmations").containsEntry("status", "queued");
        assertThat(resumption.get("interrupt_requested_at")).isNotNull();

        TurnResult result = node.claimAndRun();

        assertThat(result)
                .isEqualTo(new TurnResult.Finished(
                        (String) resumption.get("id"), TurnOutcome.INTERRUPTED, StopReason.INTERRUPTED));
        assertThat(fixture.model.requests()).as("恢复轮不再调用模型").hasSize(1);
        assertThat(fixture.writeCalls.toolUseIds()).isEmpty();
        List<StoredEvent> results = ofType(fixture.events(node, session), EventType.AGENT_TOOL_RESULT);
        assertThat(results)
                .singleElement()
                .satisfies(event -> assertThat(event.data())
                        .contains("\"permission\": \"denied\"")
                        .contains("用户中断了本轮"));
        assertThat(sessionStatus(session)).isEqualTo("idle");
    }

    // ------------------------------------------------------------------ 删除会话

    @Test
    void delete_running_cancelsTheCurrentTurn() throws Exception {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.toolCall("call_1", LOOKUP, Map.of("text", "订单 42")))
                .respond(r -> r.text("不应到达"))
                .build());
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        fixture.post(node, session, message("查订单 42", Delivery.QUEUE));
        node.channelListener().start();
        try {
            fixture.lookupCalls.close();
            TurnExecution execution = node.claim();
            CompletableFuture<TurnResult> running = execution.run().toFuture();
            try {
                fixture.lookupCalls.awaitEntered();
                PlatformContexts.run(
                        session.context(), () -> fixture.sessions(node).delete(session.sessionId()));
                // 节点收到通知后立即续租，发现轮次已取消，放弃本次执行
                assertThat(running.get(30, TimeUnit.SECONDS)).isInstanceOf(TurnResult.Abandoned.class);
            } finally {
                fixture.lookupCalls.open();
            }
            assertThat(DbAssertions.row(
                            "SELECT status FROM rt.turns WHERE id = ?",
                            execution.turn().turnId()))
                    .containsEntry("status", "cancelled");
            assertThat(types(fixture.events(node, session))).last().isEqualTo("session.status_terminated");
            assertThat(fixture.model.requests()).hasSize(1);
        } finally {
            node.channelListener().stop();
        }
    }

    @Test
    void delete_awaitingAction_cancelsTheTurnAndVoidsItsActions() {
        WorkerFixture fixture = new WorkerFixture(
                ScriptedModel.builder()
                        .respond(r -> r.toolCall("call_w", WRITE, Map.of("text", "新记录")))
                        .build(),
                WorkerFixture::lookupOnlyAllowed);
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        fixture.post(node, session, message("写一条记录", Delivery.QUEUE));
        node.claimAndRun();
        String awaitingTurn = turnId(session);

        PlatformContexts.run(session.context(), () -> fixture.sessions(node).delete(session.sessionId()));

        assertThat(DbAssertions.row("SELECT status FROM rt.turns WHERE id = ?", awaitingTurn))
                .containsEntry("status", "cancelled");
        assertThat(fixture.actions(session))
                .singleElement()
                .satisfies(action -> assertThat(action.status()).isEqualTo(PendingActionStatus.EXPIRED));
        assertThat(sessionStatus(session)).isEqualTo("terminated");
    }

    // ------------------------------------------------------------------ 辅助

    private static UserEvent approve(StoredPendingAction action) {
        return new UserEvent.ToolConfirmation(action.id(), true, null, null, RememberScope.NONE);
    }

    private static List<Map<String, Object>> inboxRows(TestSession session) {
        return DbAssertions.rows(
                "SELECT event_id, delivery, status, consumed_turn_id, consumed_epoch FROM rt.inbox_messages "
                        + "WHERE session_id = ? ORDER BY created_at, id",
                session.sessionId());
    }

    private static String sessionStatus(TestSession session) {
        return (String) DbAssertions.row("SELECT status FROM rt.sessions WHERE id = ?", session.sessionId())
                .get("status");
    }

    private static String turnId(TestSession session) {
        return (String) WorkerFixture.latestTurn(session).get("id");
    }
}
