package cn.refinex.meridian.engine.agentscope.turn;

import static cn.refinex.meridian.engine.agentscope.turn.EngineHarness.userText;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.assembly.AgentHandle;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.spi.engine.TurnContext;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import io.agentscope.core.ReActAgent;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * P11 的回归：同一个共享的 {@code HarnessAgent} 轮转执行许多线程，AgentScope 2.0.4 的 {@code ReActAgent} 按 {@code (userId,
 * sessionId)} 缓存状态、权限引擎与版本号（{@code stateCache}、{@code permissionEngineCache}、{@code slotVersions}），只有
 * {@code clearStateCache} 才释放。引擎每轮开始前与结束时各清一次，所以执行中每个线程占一个槽，结束后不留下任何槽
 * （架构设计「内核适配层」，报告 {@code docs/poc/P11-内存.md}）。
 */
class StateCacheReleaseTest {

    private static final PermissionPolicy OPEN = PermissionPolicy.empty();

    @Test
    void aRunningTurnHoldsOneSlotThatIsReleasedWhenTheTurnEnds() throws Exception {
        AgentScopeEngineScenarioTest.LatchTool slow = new AgentScopeEngineScenarioTest.LatchTool();
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_s", "slow", Map.of("text", "x")))
                .respond(r -> r.text("完成"))
                .build();
        EngineHarness harness = EngineHarness.create(model, slow);
        TurnContext ctx = harness.claim("thr_cache", "trn_1", 1, OPEN);
        List<EngineEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        harness.stream(ctx, userText("慢慢来"))
                .doOnNext(events::add)
                .doFinally(s -> done.countDown())
                .subscribe();
        assertThat(slow.started.await(10, TimeUnit.SECONDS)).isTrue();
        ReActAgent agent = delegate(harness);
        assertThat(cacheSizes(agent)).as("执行中：本线程一个槽").containsExactly(1, 1, 1);

        slow.release.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(cacheSizes(agent)).as("结束后（finally 中 clearStateCache）").containsExactly(0, 0, 0);
    }

    @Test
    void rotatingThroughManyThreadsLeavesNoSlotsBehind() throws Exception {
        int threads = 200;
        ScriptedModel model = ScriptedModel.builder()
                .respondTo((request, r) -> r.text("收到：" + request.lastUserText()))
                .build();
        EngineHarness harness = EngineHarness.create(model);

        for (int i = 0; i < threads; i++) {
            harness.run(harness.claim("thr_rotate_" + i, "trn_" + i, 1, OPEN), userText("第 " + i + " 个线程"));
        }

        assertThat(cacheSizes(delegate(harness))).containsExactly(0, 0, 0);
        assertThat(model.requests()).hasSize(threads);
    }

    private static ReActAgent delegate(EngineHarness harness) {
        AgentHandle handle = harness.acquire();
        try {
            return handle.agent().getDelegate();
        } finally {
            harness.instances.release(handle);
        }
    }

    /** {@code stateCache}、{@code permissionEngineCache}、{@code slotVersions} 的大小，按此顺序。 */
    private static List<Integer> cacheSizes(ReActAgent agent) throws ReflectiveOperationException {
        return List.of(size(agent, "stateCache"), size(agent, "permissionEngineCache"), size(agent, "slotVersions"));
    }

    private static int size(ReActAgent agent, String name) throws ReflectiveOperationException {
        Field field = ReActAgent.class.getDeclaredField(name);
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(agent)).size();
    }
}
