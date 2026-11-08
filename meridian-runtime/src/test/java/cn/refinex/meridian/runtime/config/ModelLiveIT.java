package cn.refinex.meridian.runtime.config;

import static cn.refinex.meridian.runtime.config.WorkerFixture.LOOKUP;
import static cn.refinex.meridian.runtime.config.WorkerFixture.ofType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.engine.agentscope.model.RoutedModel;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.TestSpecs;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.engine.spi.event.StopReason;
import cn.refinex.meridian.engine.spi.event.UserEvent.Delivery;
import cn.refinex.meridian.engine.spi.spec.CompactionSpec;
import cn.refinex.meridian.engine.spi.spec.ModelSpec;
import cn.refinex.meridian.runtime.dispatch.domain.TurnOutcome;
import cn.refinex.meridian.runtime.modelrouting.domain.BudgetLevel;
import cn.refinex.meridian.runtime.session.app.TurnResult;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.TestSessions.TestSession;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 真实模型测试（T09a-A08）：DashScope 兼容端点（{@code compatible-mode/v1}），模型取 {@code MERIDIAN_LIVE_CHAT_MODEL}（默认
 * {@code qwen-plus}），凭据取环境变量 {@code DASHSCOPE_API_KEY}，没有设置时跳过。只断言结构性条件，不断言措辞（测试规范
 * 「真实模型测试」）。
 *
 * <p>成本：最大输出 256 token，压缩用 ASCII 文本（真实分词约 4 个字符 1 个 token），整个类约 6 到 8 次调用，远低于 20 次的上限。
 */
@Tag("live")
class ModelLiveIT {

    private static final String MODEL_ID = "mdl_live";
    private static final String CHAT_MODEL =
            Objects.requireNonNullElse(System.getenv("MERIDIAN_LIVE_CHAT_MODEL"), "qwen-plus");

    @TempDir
    Path directory;

    private ModelRoutingFixture routing;

    @BeforeAll
    static void requireApiKey() {
        String key = System.getenv("DASHSCOPE_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "没有设置 DASHSCOPE_API_KEY，跳过真实模型测试");
    }

    @AfterEach
    void close() {
        if (routing != null) {
            routing.close();
        }
    }

    @Test
    void harnessAgentCompletesATurnWithAToolCall() {
        WorkerFixture fixture = fixture(
                131_072, spec -> spec.systemPrompt("你是订单助手。回答任何关于订单的问题之前，必须先调用 lookup 工具查询，参数 text 填订单号；拿到结果后用一句话回答。"));
        WorkerNode node = fixture.node("node-live");
        TestSession session = fixture.session();

        fixture.send(node, session, "订单 A100 现在是什么状态？", Delivery.QUEUE);
        TurnResult result = node.claimAndRun();

        assertThat(result)
                .isEqualTo(new TurnResult.Finished(result.turnId(), TurnOutcome.COMPLETED, StopReason.END_TURN));
        List<StoredEvent> events = fixture.events(node, session);
        assertThat(ofType(events, EventType.AGENT_TOOL_USE))
                .as("产生了工具调用，工具名正确，入参可解析")
                .isNotEmpty()
                .allSatisfy(use -> assertThat(use.data())
                        .contains("\"name\": \"" + LOOKUP + "\"")
                        .contains("\"text\""));
        assertThat(ofType(events, EventType.AGENT_TOOL_RESULT)).isNotEmpty();
        assertThat(fixture.lookupCalls.toolUseIds()).as("工具真的执行了").isNotEmpty();
        assertThat(ofType(events, EventType.AGENT_MESSAGE))
                .as("最终有文本回答")
                .last()
                .satisfies(message -> assertThat(message.data()).contains("\"text\""));
        List<Map<String, Object>> usage = ModelRoutingFixture.usage(session.tenantId());
        assertThat(usage).as("每次模型调用都记了供应商报告的用量").hasSizeGreaterThanOrEqualTo(2).allSatisfy(record -> {
            assertThat(record).containsEntry("usage_source", "provider").containsEntry("upstream_model", CHAT_MODEL);
            assertThat(((Number) record.get("input_tokens")).intValue()).isPositive();
            assertThat(((Number) record.get("output_tokens")).intValue()).isPositive();
        });
    }

    @Test
    void streamingRecordsTheFirstTokenTimeAndSettlesTheUsage() {
        routing = ModelRoutingFixture.live(directory).model(MODEL_ID, CHAT_MODEL, 131_072, 128);
        RoutedModel model = routing.routedModel(ModelSpec.of(MODEL_ID), RoutedModel.RetryPolicy.DEFAULT);
        String sessionId = "ses_" + Ids.newUlid();
        List<Msg> messages = List.of(Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text("用三句话介绍杭州。").build())
                .build());

        List<ChatResponse> chunks = model.stream(messages, null, null)
                .contextWrite(PlatformContexts.write(routing.context(sessionId)))
                .collectList()
                .block(Duration.ofMinutes(2));

        String text = chunks.stream()
                .flatMap(chunk -> chunk.getContent().stream())
                .filter(TextBlock.class::isInstance)
                .map(block -> ((TextBlock) block).getText())
                .collect(Collectors.joining());
        assertThat(chunks).as("流式返回多个增量").hasSizeGreaterThan(2);
        assertThat(text).isNotBlank();
        assertThat(routing.registry
                        .get("meridian.model.first.token")
                        .tag("model", MODEL_ID)
                        .timer()
                        .count())
                .as("首 token 时间被记录")
                .isEqualTo(1);
        Map<String, Object> usage = routing.usage().getFirst();
        assertThat(usage).containsEntry("usage_source", "provider");
        long billed =
                ((Number) usage.get("input_tokens")).longValue() + ((Number) usage.get("output_tokens")).longValue();
        assertThat(billed).isPositive();
        assertThat(routing.ledger.used(routing.tenantId, BudgetLevel.SESSION, sessionId))
                .as("usage 被结算：预扣按实际用量修正")
                .isEqualTo(billed);
    }

    @Test
    void aSmallContextWindowTriggersARealCompaction() {
        // 上下文窗口 3000、预留 500：阈值 2500（按 2.0.4 TokenCounterUtil 的 2.5 字符一个 token），第二轮超过阈值
        WorkerFixture fixture = fixture(
                3_000,
                spec -> spec.systemPrompt("You are a concise assistant. Reply in one short sentence.")
                        .compaction(new CompactionSpec(500)));
        WorkerNode node = fixture.node("node-live");
        TestSession session = fixture.session();
        String notes = "Shipping note: the Hangzhou warehouse ships orders on weekdays before 5 pm. ".repeat(72);

        fixture.send(node, session, "Please remember these notes for later. " + notes, Delivery.QUEUE);
        node.claimAndRun();
        fixture.send(
                node,
                session,
                "Which city is the warehouse in? " + "Answer briefly, the details above matter. ".repeat(48),
                Delivery.QUEUE);
        TurnResult result = node.claimAndRun();

        assertThat(result)
                .isEqualTo(new TurnResult.Finished(result.turnId(), TurnOutcome.COMPLETED, StopReason.END_TURN));
        List<StoredEvent> events = fixture.events(node, session);
        assertThat(ofType(events, EventType.AGENT_CONTEXT_COMPACTED))
                .as("触发了一次压缩")
                .hasSize(1);
        List<Map<String, Object>> usage = ModelRoutingFixture.usage(session.tenantId());
        assertThat(usage)
                .filteredOn(record -> "compaction".equals(record.get("purpose")))
                .singleElement()
                .satisfies(record -> assertThat(((Number) record.get("output_tokens")).intValue())
                        .as("摘要非空")
                        .isPositive());
        String state = DbAssertions.list(
                        "SELECT state::text FROM rt.agent_states WHERE thread_id = ?",
                        String.class,
                        session.mainThreadId())
                .stream()
                .collect(Collectors.joining("\n"));
        assertThat(state)
                .contains("__compaction_summary__")
                .contains("Here is a summary of the conversation to date")
                .doesNotContain("(Summarization failed")
                .doesNotContain("(Summary unavailable)");
    }

    private WorkerFixture fixture(int contextWindow, UnaryOperator<TestSpecs.AgentSpecBuilder> agent) {
        routing = ModelRoutingFixture.live(directory).model(MODEL_ID, CHAT_MODEL, contextWindow, 256);
        RoutedModel routed = routing.routedModel(ModelSpec.of(MODEL_ID), RoutedModel.RetryPolicy.DEFAULT);
        return new WorkerFixture(
                ScriptedModel.builder().build(), spec -> agent.apply(spec.model(ModelSpec.of(MODEL_ID))), routed);
    }
}
