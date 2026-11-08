package cn.refinex.meridian.runtime.config;

import static cn.refinex.meridian.runtime.config.WorkerFixture.LOOKUP;
import static cn.refinex.meridian.runtime.config.WorkerFixture.ofType;
import static cn.refinex.meridian.runtime.config.WorkerFixture.types;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.event.UserEvent.Delivery;
import cn.refinex.meridian.runtime.dispatch.domain.TurnOutcome;
import cn.refinex.meridian.runtime.session.app.TurnExecution;
import cn.refinex.meridian.runtime.session.app.TurnResult;
import cn.refinex.meridian.runtime.session.app.TurnWorkerLifecycle;
import cn.refinex.meridian.runtime.session.app.WorkerProperties;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.FailingDataSource;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import reactor.core.publisher.Mono;

/**
 * T06-A01、A02：Worker 认领并执行一轮，事件按序写库并出现在 Redis Stream 中，会话回到 {@code idle}；本轮执行中到达的 queue 消息
 * 在本轮结束时合并成下一轮并被自动执行。
 */
class TurnWorkerIT {

    /** 停止 Worker 会触发 AgentScope 的 JVM 级优雅停机，之后的用例必须恢复它。 */
    @AfterEach
    void resetGracefulShutdown() {
        GracefulShutdownManager.getInstance().resetForTesting();
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test
    void workerRunsATurnEndToEndPersistsEventsInOrderAndPublishesThem() {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.toolCall("call_1", LOOKUP, Map.of("text", "订单 42")))
                .respond(r -> r.text("订单 42 已发货", 3))
                .build());
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        String eventId = fixture.send(node, session, "查一下订单 42", Delivery.QUEUE);

        TurnExecution execution = node.claim();
        TurnResult result = execution.run().block(WorkerNode.RUN_TIMEOUT);

        String turnId = execution.turn().turnId();
        assertThat(result).isEqualTo(new TurnResult.Finished(turnId, TurnOutcome.COMPLETED, StopReason.END_TURN));
        List<StoredEvent> events = fixture.events(node, session);
        assertThat(events)
                .extracting(StoredEvent::seq)
                .as("seq 从 1 连续")
                .containsExactlyElementsOf(
                        LongStream.rangeClosed(1, events.size()).boxed().toList());
        assertThat(types(events))
                .containsExactly(
                        "user.message",
                        "session.status_running",
                        "session.message_consumed",
                        "span.model_request_start",
                        "agent.tool_use",
                        "span.model_request_end",
                        "agent.tool_result",
                        "span.model_request_start",
                        "agent.message",
                        "span.model_request_end",
                        "session.status_idle");
        assertThat(events.subList(1, events.size()))
                .allSatisfy(e -> assertThat(e.turnId()).isEqualTo(turnId));
        assertThat(ofType(events, EventType.SESSION_STATUS_IDLE).getFirst().data())
                .contains("end_turn");
        assertThat(ofType(events, EventType.SESSION_MESSAGE_CONSUMED).getFirst().data())
                .contains(eventId);

        List<MapRecord<String, Object, Object>> stream = WorkerFixture.awaitStream(session.sessionId(), events.size());
        assertThat(stream.stream()
                        .filter(r -> r.getValue().containsKey("seq"))
                        .map(r -> Long.parseLong((String) r.getValue().get("seq"))))
                .as("持久化事件按 seq 顺序出现在 ev:{session_id}")
                .containsExactlyElementsOf(events.stream().map(StoredEvent::seq).toList());
        assertThat(stream)
                .as("只在流上的增量也发布了")
                .anyMatch(r -> "event_delta".equals(r.getValue().get("type"))
                        && !r.getValue().containsKey("seq"));
        assertThat((String) stream.getLast().getValue().get("envelope"))
                .contains("\"type\":\"session.status_idle\"", "\"turn_id\":\"" + turnId + "\"");

        Map<String, Object> turn = DbAssertions.turn(turnId);
        assertThat(turn).containsEntry("status", "completed").containsEntry("stop_reason", "end_turn");
        assertThat(DbAssertions.running(session.tenantId())).isZero();
        assertThat(DbAssertions.list("SELECT status FROM rt.sessions WHERE id = ?", String.class, session.sessionId()))
                .containsExactly("idle");
        assertThat(DbAssertions.list(
                        "SELECT payload->>'action' FROM rt.outbox WHERE topic = 'audit' AND payload->>'turn_id' = ?"
                                + " ORDER BY id",
                        String.class,
                        turnId))
                .as("有副作用的工具执行（T10a，账本结束时写入）、工具调用的权限判定（T08）与轮次结束")
                .containsExactly("tool.executed", "permission.decided", "turn.finished");
        assertThat(node.node().registry().heldThreadIds())
                .as("finally 中释放了本节点的条目")
                .isEmpty();
        assertThat(fixture.lookupCalls.toolUseIds()).containsExactly("call_1");
    }

    @Test
    void theWorkerLoopSurvivesClaimFailuresAndClaimsOnceTheDatabaseIsBack() {
        WorkerFixture fixture = new WorkerFixture(
                ScriptedModel.builder().respond(r -> r.text("数据库恢复后完成")).build());
        FailingDataSource database = new FailingDataSource(RuntimeTestDatabase.dataSource());
        WorkerNode node = fixture.node("node-a", WorkerProperties.defaults(), s -> s, database);
        TestSession session = fixture.session();
        fixture.send(node, session, "你好", Delivery.QUEUE);
        TurnWorkerLifecycle lifecycle = node.lifecycle();
        database.failing(true);
        lifecycle.start();
        try {
            // 轮询每 100 毫秒一次，这段时间里的认领都失败
            sleepQuietly(Duration.ofMillis(500));
            assertThat(lifecycle.isRunning()).isTrue();
            database.failing(false);

            List<StoredEvent> events = WorkerFixture.await(
                    () -> fixture.events(node, session),
                    all -> !ofType(all, EventType.SESSION_STATUS_IDLE).isEmpty());
            assertThat(events.getLast().data()).contains("end_turn");
        } finally {
            database.failing(false);
            Mono.fromRunnable(lifecycle::stop).block(WorkerNode.RUN_TIMEOUT);
        }
    }

    @Test
    void queueMessagesArrivingDuringATurnBecomeTheNextTurnAndRunAutomatically() throws Exception {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.toolCall("call_1", LOOKUP, Map.of("text", "a")))
                .respond(r -> r.text("第一轮完成"))
                .respond(r -> r.text("第二轮完成"))
                .build());
        WorkerNode node = fixture.node("node-a");
        TestSession session = fixture.session();
        TurnWorkerLifecycle lifecycle = node.lifecycle();
        fixture.lookupCalls.close();
        fixture.send(node, session, "第一条", Delivery.QUEUE);
        lifecycle.start();
        try {
            fixture.lookupCalls.awaitEntered();
            String second = fixture.send(node, session, "运行中发来的第二条", Delivery.QUEUE);
            String third = fixture.send(node, session, "运行中发来的第三条", Delivery.QUEUE);
            fixture.lookupCalls.open();

            List<StoredEvent> events = WorkerFixture.await(
                    () -> fixture.events(node, session),
                    all -> ofType(all, EventType.SESSION_STATUS_IDLE).size() == 2);

            List<StoredEvent> consumed = ofType(events, EventType.SESSION_MESSAGE_CONSUMED);
            assertThat(consumed).hasSize(2);
            assertThat(consumed.get(1).data()).as("两条 queue 消息合并成一轮").contains(second, third);
            assertThat(consumed.get(1).turnId()).isNotEqualTo(consumed.get(0).turnId());
            assertThat(fixture.model.request(2).messages())
                    .extracting(m -> m.getTextContent())
                    .as("第二轮按到达顺序带上两条消息")
                    .containsSubsequence("运行中发来的第二条", "运行中发来的第三条");
            assertThat(DbAssertions.list(
                            "SELECT consumed_turn_id FROM rt.inbox_messages WHERE event_id IN (?, ?)",
                            String.class,
                            second,
                            third))
                    .containsOnly(consumed.get(1).turnId());
        } finally {
            fixture.lookupCalls.open();
            Mono.fromRunnable(lifecycle::stop).block(WorkerNode.RUN_TIMEOUT);
        }
    }
}
