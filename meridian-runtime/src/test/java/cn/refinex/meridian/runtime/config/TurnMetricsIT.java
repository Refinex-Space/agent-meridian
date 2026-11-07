package cn.refinex.meridian.runtime.config;

import static cn.refinex.meridian.runtime.config.WorkerFixture.LOOKUP;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.UserEvent.Delivery;
import cn.refinex.meridian.runtime.dispatch.app.QueueDepthGauge;
import cn.refinex.meridian.runtime.session.app.TurnExecution;
import cn.refinex.meridian.runtime.session.app.TurnResult;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * T06-A09：本任务的六个指标已注册并有值——{@code meridian_turns_total}、{@code meridian_turn_duration_seconds}、
 * {@code meridian_turn_queue_wait_seconds}、{@code meridian_dispatch_queue_depth}、{@code meridian_turn_recoveries_total}、
 * {@code meridian_lease_lost_total}（Micrometer 名用点分隔，见 {@code TurnMetrics}）。
 */
class TurnMetricsIT {

    @Test
    void allTurnMetricsAreRecorded() throws Exception {
        WorkerFixture fixture = new WorkerFixture(ScriptedModel.builder()
                .respond(r -> r.text("好"))
                .respond(r -> r.toolCall("call_1", LOOKUP, Map.of("text", "a")))
                .respond(r -> r.text("续跑完成"))
                .build());
        WorkerNode nodeA = fixture.node("node-a");
        WorkerNode nodeB = fixture.node("node-b");
        MeterRegistry metersA = nodeA.node().meters();
        MeterRegistry metersB = nodeB.node().meters();
        QueueDepthGauge depth = new QueueDepthGauge(nodeA.node().queue(), metersA);
        TestSession first = fixture.session();
        TestSession second = fixture.session();
        fixture.send(nodeA, first, "第一个会话", Delivery.QUEUE);
        fixture.clock.advance(Duration.ofSeconds(3));
        fixture.send(nodeA, second, "第二个会话", Delivery.QUEUE);
        depth.refresh();
        assertThat(metersA.get("meridian.dispatch.queue.depth").gauge().value()).isEqualTo(2.0);

        assertThat(nodeA.claimAndRun()).isInstanceOf(TurnResult.Finished.class);

        assertThat(metersA.get("meridian.turns")
                        .tag("status", "completed")
                        .tag("stop_reason", "end_turn")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(metersA.get("meridian.turn.duration")
                        .tag("agent_version", fixture.spec.agentVersionId())
                        .timer()
                        .count())
                .isEqualTo(1);
        assertThat(metersA.get("meridian.turn.queue.wait")
                        .tag("tenant", first.tenantId())
                        .timer()
                        .totalTime(TimeUnit.SECONDS))
                .as("第一个会话入队 3 秒后才被认领")
                .isEqualTo(3.0);

        // 第二个会话：node-a 执行到一半被 node-b 接管，node-a 续租时发现租约丢失，node-b 恢复执行
        fixture.lookupCalls.close();
        try {
            TurnExecution stale = nodeA.claim();
            CompletableFuture<TurnResult> running = stale.run().toFuture();
            fixture.lookupCalls.awaitEntered();
            WorkerFixture.await(
                    () -> fixture.events(nodeA, second),
                    events -> events.stream().anyMatch(e -> e.type() == EventType.SPAN_MODEL_REQUEST_END));
            fixture.clock.advance(Duration.ofSeconds(31));
            WorkerFixture.reap(nodeB);
            nodeA.node().renewer().renewAll();
            assertThat(running.get(30, TimeUnit.SECONDS)).isInstanceOf(TurnResult.Abandoned.class);
            assertThat(nodeB.claimAndRun()).isInstanceOf(TurnResult.Finished.class);
        } finally {
            fixture.lookupCalls.open();
        }

        assertThat(metersA.get("meridian.lease.lost").counter().count()).isEqualTo(1.0);
        assertThat(metersB.get("meridian.turn.recoveries")
                        .tag("mode", "replay")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(metersB.get("meridian.turns")
                        .tag("status", "completed")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(metersA.get("meridian.turn.duration").timer().count())
                .as("被放弃的执行也计时")
                .isEqualTo(2);
        depth.refresh();
        assertThat(metersA.get("meridian.dispatch.queue.depth").gauge().value()).isZero();
    }
}
