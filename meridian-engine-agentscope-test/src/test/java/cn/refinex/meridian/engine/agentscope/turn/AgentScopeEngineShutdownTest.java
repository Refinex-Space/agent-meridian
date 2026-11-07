package cn.refinex.meridian.engine.agentscope.turn;

import static cn.refinex.meridian.engine.agentscope.turn.EngineHarness.PROJECT;
import static cn.refinex.meridian.engine.agentscope.turn.EngineHarness.userText;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.agentscope.turn.AgentScopeEngineScenarioTest.LatchTool;
import cn.refinex.meridian.engine.spi.engine.TurnContext;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.event.SessionEvent.RescheduleReason;
import cn.refinex.meridian.engine.spi.port.AgentStateRepository.StateKey;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 节点下线（T04-A04 的"关机中断"）：{@code shutdown} 以 SYSTEM 来源中断执行，AgentScope 保存中途状态后以
 * {@code AgentShuttingDownException} 结束，引擎只输出 {@code session.status_rescheduled}。
 *
 * <p>{@link GracefulShutdownManager} 是 JVM 级单例，每个用例结束后复位，避免影响同一 JVM 中的其他测试。
 */
class AgentScopeEngineShutdownTest {

    @AfterEach
    void resetShutdownManager() {
        GracefulShutdownManager.getInstance().resetForTesting();
    }

    @Test
    void engineSetsTheShutdownTimeoutFromTheArchitecture() {
        EngineHarness.create(ScriptedModel.builder().build());

        assertThat(GracefulShutdownManager.getInstance().getConfig().shutdownTimeout())
                .isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void shutdownInterruptsAtTheNextCheckpointAndReschedulesWithoutAnError() throws Exception {
        LatchTool slow = new LatchTool();
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_s", "slow", Map.of("text", "x")))
                .respond(r -> r.text("不应被调用"))
                .build();
        EngineHarness harness = EngineHarness.create(model, slow);
        TurnContext ctx = harness.claim("thr_shutdown", "trn_1", 4, PermissionPolicy.empty());
        List<EngineEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        harness.stream(ctx, userText("慢慢来"))
                .doOnNext(events::add)
                .doFinally(s -> done.countDown())
                .subscribe();
        assertThat(slow.started.await(10, TimeUnit.SECONDS)).isTrue();
        CountDownLatch shutdownDone = new CountDownLatch(1);
        harness.engine
                .shutdown(Duration.ofSeconds(20))
                .doFinally(s -> shutdownDone.countDown())
                .subscribe();
        slow.release.countDown();

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(shutdownDone.await(30, TimeUnit.SECONDS))
                .as("全部执行结束后 shutdown 完成")
                .isTrue();
        EngineHarness.assertExactlyOneTerminalAtTheEnd(events);
        assertThat(events.getLast()).isEqualTo(new SessionEvent.StatusRescheduled(RescheduleReason.NODE_SHUTDOWN));
        assertThat(events).noneMatch(SessionEvent.Error.class::isInstance);
        assertThat(model.requests()).as("工具执行完毕后，下一次推理前停止").hasSize(1);
        assertThat(harness.states.contentEpoch(PROJECT + ":_", "thr_shutdown"))
                .as("停机时保存了中途状态，恢复走 RecoveryOnly")
                .hasValue(4);
        assertThat(harness.states.find(new StateKey(PROJECT + ":_", "thr_shutdown", "agent_state")))
                .hasValueSatisfying(s -> assertThat(s.json()).contains("\"shutdown_interrupted\":true"));
    }

    @Test
    void turnsStartedWhileShuttingDownAreRescheduled() {
        ScriptedModel model =
                ScriptedModel.builder().respond(r -> r.text("不应被调用")).build();
        EngineHarness harness = EngineHarness.create(model);
        GracefulShutdownManager.getInstance().performGracefulShutdown();

        List<EngineEvent> events =
                harness.run(harness.claim("thr_late", "trn_1", 1, PermissionPolicy.empty()), userText("你好"));

        assertThat(events).containsExactly(new SessionEvent.StatusRescheduled(RescheduleReason.NODE_SHUTDOWN));
        assertThat(model.requests()).isEmpty();
    }
}
