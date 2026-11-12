package cn.refinex.meridian.engine.agentscope.turn;

import static cn.refinex.meridian.engine.agentscope.turn.EngineHarness.PROJECT;
import static cn.refinex.meridian.engine.agentscope.turn.EngineHarness.of;
import static cn.refinex.meridian.engine.agentscope.turn.EngineHarness.userText;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.middleware.CompactionGuardMiddleware;
import cn.refinex.meridian.engine.agentscope.model.ProviderClients;
import cn.refinex.meridian.engine.agentscope.model.RoutedModelFactory;
import cn.refinex.meridian.engine.agentscope.testkit.model.InMemoryModelGateway;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.agentscope.testkit.model.StaticProviderClientFactory;
import cn.refinex.meridian.engine.spi.engine.TurnContext;
import cn.refinex.meridian.engine.spi.error.ModelErrorCode;
import cn.refinex.meridian.engine.spi.event.AgentOutputEvent;
import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.engine.spi.event.SessionEvent.RescheduleReason;
import cn.refinex.meridian.engine.spi.event.SessionEvent.RetryStatus;
import cn.refinex.meridian.engine.spi.event.SpanEvent;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.port.ModelGateway.CallPurpose;
import cn.refinex.meridian.engine.spi.port.ModelGateway.ModelCallRecord;
import cn.refinex.meridian.engine.spi.port.ProviderRetryPolicy;
import cn.refinex.meridian.engine.spi.spec.CompactionSpec;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpTransportException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 压缩守护的场景（P13，T09a-A04 与 A05 的内核部分）：真实装配的 {@code HarnessAgent} 加 {@code RoutedModel}，底层供应商是
 * {@code ScriptedModel}，模型目录的上下文窗口 2000、压缩预留 500，第二轮的会话超过阈值 1500 时触发真实的 2.0.4 压缩。
 *
 * <p>第一轮的输入约 1200 个 token（按 2.0.4 {@code TokenCounterUtil} 的 2.5 字符一个 token），低于阈值；第二轮的会话约 2400，
 * 超过阈值，且大于 2.0.4 保留尾部的下限 2000，压缩器会把第一条消息摘要掉。
 */
class CompactionGuardScenarioTest {

    private static final String MODEL_ID = "mdl_test";
    private static final String UPSTREAM = "up-compact";
    private static final int WINDOW = 2_000;
    private static final int RESERVED = 500;
    private static final String FIRST = "甲".repeat(3_000);
    private static final String SECOND = "乙".repeat(3_000);
    private static final String USER_ID = PROJECT + ":_";

    private final InMemoryModelGateway gateway = new InMemoryModelGateway().route(MODEL_ID, UPSTREAM, WINDOW, 200);

    @Test
    void compactionIsMeteredReplacesHistoryAndEmitsTheEvent() {
        ScriptedModel upstream = ScriptedModel.builder()
                .respond(r -> r.text("答复一").usage(1_300, 3))
                .respond(r -> r.text("## 目标\n用户发了两段长文本。").usage(1_250, 12))
                .respond(r -> r.text("答复二").usage(1_400, 3))
                .build();
        EngineHarness harness = harness(upstream, ProviderRetryPolicy.DEFAULT);

        List<EngineEvent> first = harness.run(claim(harness, "trn_1", 1), userText(FIRST));
        assertThat(of(first, AgentOutputEvent.ContextCompacted.class))
                .as("第一轮低于阈值，不压缩")
                .isEmpty();
        assertThat(gateway.prechecks()).as("不会压缩时不预检").isEmpty();

        List<EngineEvent> second = harness.run(claim(harness, "trn_2", 2), userText(SECOND));

        assertThat(of(second, AgentOutputEvent.ContextCompacted.class))
                .as("压缩前五条会话消息（两轮各一条平台上下文消息与用户消息、一条回答），压缩后为摘要加保留的三条")
                .containsExactly(new AgentOutputEvent.ContextCompacted(5, 4));
        assertThat(second.indexOf(new AgentOutputEvent.ContextCompacted(5, 4)))
                .as("压缩事件在本次推理的模型请求之前")
                .isLessThan(second.indexOf(
                        of(second, SpanEvent.ModelRequestStart.class).getFirst()));
        assertThat(second.getLast()).isEqualTo(new SessionEvent.StatusIdle(StopReason.END_TURN));
        assertThat(gateway.prechecks()).as("压缩前预检了一次").hasSize(1);
        assertThat(gateway.settlements())
                .as("压缩器的内部调用同样经过预扣与计量")
                .extracting(ModelCallRecord::purpose)
                .containsExactly(CallPurpose.REASONING, CallPurpose.COMPACTION, CallPurpose.REASONING);
        assertThat(upstream.request(1).lastUserText())
                .as("压缩调用使用平台的摘要提示词，输入是被压缩的第一条消息")
                .contains("交接摘要")
                .contains("甲甲甲")
                .doesNotContain("乙乙乙");
        assertThat(harness.states.jsonOf(USER_ID, "thr_compact"))
                .contains("__compaction_summary__")
                .contains("用户发了两段长文本")
                .doesNotContain("甲甲甲");
    }

    @Test
    void exhaustedBudgetBlocksCompactionBeforeItStarts() {
        ScriptedModel upstream = ScriptedModel.builder()
                .respond(r -> r.text("答复一").usage(1_300, 3))
                .build();
        EngineHarness harness = harness(upstream, ProviderRetryPolicy.DEFAULT);
        harness.run(claim(harness, "trn_1", 1), userText(FIRST));
        gateway.budgetLimit(gateway.used() + 100);

        List<EngineEvent> second = harness.run(claim(harness, "trn_2", 2), userText(SECOND));

        assertThat(second)
                .filteredOn(SessionEvent.Error.class::isInstance)
                .singleElement()
                .satisfies(e ->
                        assertThat(((SessionEvent.Error) e).code()).isEqualTo(ModelErrorCode.BUDGET_EXCEEDED.code()));
        assertThat(second.getLast()).isEqualTo(new SessionEvent.StatusIdle(StopReason.BUDGET_EXCEEDED));
        assertThat(upstream.requests()).as("压缩器没有发起模型调用").hasSize(1);
        assertThat(gateway.reservations()).as("预检不占用预算，也没有新的预扣").isEqualTo(1);
        assertThat(harness.states.jsonOf(USER_ID, "thr_compact")).doesNotContain("__compaction_summary__");
    }

    @Test
    void failedSummaryIsInterceptedAndDoesNotReplaceTheHistory() {
        ScriptedModel upstream = ScriptedModel.builder()
                .respond(r -> r.text("答复一").usage(1_300, 3))
                .failBeforeFirstChunk(new HttpTransportException("供应商返回 400", 400, "{}"))
                .build();
        EngineHarness harness = harness(upstream, ProviderRetryPolicy.DEFAULT);
        harness.run(claim(harness, "trn_1", 1), userText(FIRST));

        List<EngineEvent> second = harness.run(claim(harness, "trn_2", 2), userText(SECOND));

        assertThat(second)
                .filteredOn(SessionEvent.Error.class::isInstance)
                .singleElement()
                .satisfies(e -> {
                    SessionEvent.Error error = (SessionEvent.Error) e;
                    assertThat(error.code()).isEqualTo(ModelErrorCode.COMPACTION_FAILED.code());
                    assertThat(error.retryStatus()).isEqualTo(RetryStatus.TERMINAL);
                });
        assertThat(second.getLast()).isEqualTo(new SessionEvent.StatusIdle(StopReason.ERROR));
        assertThat(of(second, AgentOutputEvent.ContextCompacted.class)).isEmpty();
        assertThat(upstream.requests()).as("摘要失败后不再发起推理调用").hasSize(2);
        String state = harness.states.jsonOf(USER_ID, "thr_compact");
        assertThat(state)
                .as("失败的摘要没有替换历史：状态里没有摘要，第一条消息仍在")
                .doesNotContain("__compaction_summary__")
                .doesNotContain("Summarization failed")
                .contains("甲甲甲")
                .as("出错后 AgentScope 保存了本轮的状态（含第二条消息），保存的是恢复后的上下文")
                .contains("乙乙乙");
    }

    @Test
    void withoutTheGuardsTheFailureTextOf204ReplacesTheHistory() {
        // 对照实验：去掉两层守护，2.0.4 的压缩器吞掉模型异常，把失败文本当摘要写进状态，推理照常继续
        ScriptedModel upstream = ScriptedModel.builder()
                .respond(r -> r.text("答复一").usage(1_300, 3))
                .failBeforeFirstChunk(new HttpTransportException("供应商返回 400", 400, "{}"))
                .respond(r -> r.text("答复二").usage(100, 3))
                .build();
        StaticProviderClientFactory providers = new StaticProviderClientFactory().register(UPSTREAM, upstream);
        Model routed = new RoutedModelFactory(
                        gateway, new ProviderClients(List.of(providers)), ProviderRetryPolicy.DEFAULT)
                .create("agv_compact", ModelSpec.of(MODEL_ID));
        EngineHarness harness = EngineHarness.createFiltered(
                routed,
                spec -> spec.model(ModelSpec.of(MODEL_ID)).compaction(new CompactionSpec(RESERVED)),
                middleware -> !(middleware instanceof CompactionGuardMiddleware)
                        && !(middleware instanceof CompactionGuardMiddleware.Inner));
        harness.run(claim(harness, "trn_1", 1), userText(FIRST));

        List<EngineEvent> second = harness.run(claim(harness, "trn_2", 2), userText(SECOND));

        assertThat(second.getLast()).isEqualTo(new SessionEvent.StatusIdle(StopReason.END_TURN));
        assertThat(harness.states.jsonOf(USER_ID, "thr_compact"))
                .contains("__compaction_summary__")
                .contains("(Summarization failed")
                .doesNotContain("甲甲甲");
    }

    @Test
    void retryableFailureInsideCompactionRequeuesTheTurn() {
        ScriptedModel upstream = ScriptedModel.builder()
                .respond(r -> r.text("答复一").usage(1_300, 3))
                .failBeforeFirstChunk(new HttpTransportException("供应商返回 503", 503, "{}"))
                .failBeforeFirstChunk(new HttpTransportException("供应商返回 503", 503, "{}"))
                .failBeforeFirstChunk(new HttpTransportException("供应商返回 503", 503, "{}"))
                .build();
        EngineHarness harness =
                harness(upstream, new ProviderRetryPolicy(2, Duration.ofMillis(1), Duration.ofSeconds(1)));
        harness.run(claim(harness, "trn_1", 1), userText(FIRST));

        List<EngineEvent> second = harness.run(claim(harness, "trn_2", 2), userText(SECOND));

        assertThat(second)
                .filteredOn(SessionEvent.Error.class::isInstance)
                .singleElement()
                .satisfies(
                        e -> assertThat(((SessionEvent.Error) e).retryStatus()).isEqualTo(RetryStatus.RETRYABLE));
        assertThat(second.getLast()).isEqualTo(new SessionEvent.StatusRescheduled(RescheduleReason.RETRYABLE_ERROR));
        assertThat(harness.states.jsonOf(USER_ID, "thr_compact")).doesNotContain("__compaction_summary__");
    }

    private EngineHarness harness(ScriptedModel upstream, ProviderRetryPolicy retryPolicy) {
        StaticProviderClientFactory providers = new StaticProviderClientFactory().register(UPSTREAM, upstream);
        Model routed = new RoutedModelFactory(gateway, new ProviderClients(List.of(providers)), retryPolicy)
                .create("agv_compact", ModelSpec.of(MODEL_ID));
        return EngineHarness.create(
                routed, spec -> spec.model(ModelSpec.of(MODEL_ID)).compaction(new CompactionSpec(RESERVED)), List.of());
    }

    private static TurnContext claim(EngineHarness harness, String turnId, long epoch) {
        return harness.claim("thr_compact", turnId, epoch, PermissionPolicy.empty());
    }
}
