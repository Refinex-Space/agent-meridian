package cn.refinex.meridian.engine.agentscope.turn;

import static cn.refinex.meridian.engine.agentscope.turn.EngineHarness.userText;
import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.assembly.AgentHandle;
import cn.refinex.meridian.engine.agentscope.middleware.BatchCheckpointMiddleware;
import cn.refinex.meridian.engine.agentscope.middleware.InterruptCheckpointMiddleware;
import cn.refinex.meridian.engine.agentscope.middleware.LeaseGuardMiddleware;
import cn.refinex.meridian.engine.agentscope.middleware.PlatformContextMiddleware;
import cn.refinex.meridian.engine.agentscope.middleware.PlatformTracingMiddleware;
import cn.refinex.meridian.engine.agentscope.middleware.RecoveryNoteMiddleware;
import cn.refinex.meridian.engine.agentscope.middleware.SteerMiddleware;
import cn.refinex.meridian.engine.agentscope.middleware.ToolSpanMiddleware;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.TestSpecs;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.agentscope.testkit.tool.RecordingTool;
import cn.refinex.meridian.engine.spi.engine.TurnContext;
import cn.refinex.meridian.engine.spi.spec.PermissionPolicy;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.shutdown.GracefulShutdownMiddleware;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.test.StepVerifier;

/**
 * 中间件的执行顺序（P13 的一部分，T04-A09）：装配后的实际链路顺序、由 span 父子关系证明的嵌套顺序、平台公共属性，
 * 以及 {@code PlatformContextMiddleware} 对缺失上下文的校验。测得的顺序记录在模块 {@code CLAUDE.md} 与
 * {@code docs/poc/README.md}。
 */
class MiddlewareChainTest {

    private static final Logger log = LoggerFactory.getLogger(MiddlewareChainTest.class);

    @RegisterExtension
    static final OpenTelemetryExtension otel = OpenTelemetryExtension.create();

    @Test
    void platformMiddlewaresWrapTheHarnessMiddlewaresInOrderOfTheirOrderValues() {
        EngineHarness harness = EngineHarness.create(ScriptedModel.builder().build());
        AgentHandle handle = harness.instances.acquire(harness.spec, TestSpecs.noneEnvironment());
        try {
            List<MiddlewareBase> chain = handle.agent().getDelegate().getMiddlewares();
            List<String> names = chain.stream()
                    .map(m -> m.getClass().getSimpleName() + "(" + m.order() + ")")
                    .toList();
            log.info("P13 中间件链（外层在前）：{}", names);

            assertThat(chain.getFirst())
                    .as("AgentScope 在构造时把优雅停机中间件放在最外层，不参与排序")
                    .isInstanceOf(GracefulShutdownMiddleware.class);
            assertThat(chain.subList(1, 9))
                    .extracting(m -> m.getClass().getSimpleName())
                    .containsExactly(
                            PlatformContextMiddleware.class.getSimpleName(),
                            PlatformTracingMiddleware.class.getSimpleName(),
                            LeaseGuardMiddleware.class.getSimpleName(),
                            RecoveryNoteMiddleware.class.getSimpleName(),
                            InterruptCheckpointMiddleware.class.getSimpleName(),
                            SteerMiddleware.class.getSimpleName(),
                            BatchCheckpointMiddleware.class.getSimpleName(),
                            ToolSpanMiddleware.class.getSimpleName());
            assertThat(chain.subList(9, chain.size()))
                    .as("harness 自带的中间件都是默认 order 1，位于平台中间件之内")
                    .allSatisfy(m -> assertThat(m.order()).isEqualTo(1));
        } finally {
            harness.instances.release(handle);
        }
    }

    @Test
    void spansNestInTheMeasuredOrderAndCarryPlatformAttributes() {
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_a", "echo", Map.of("text", "a"))
                        .toolCall("call_b", "echo", Map.of("text", "b")))
                .respond(r -> r.text("完成"))
                .build();
        EngineHarness harness = EngineHarness.create(model, RecordingTool.echo("echo"));
        TurnContext ctx = harness.claim("thr_spans", "trn_spans", 1, PermissionPolicy.empty());

        harness.run(ctx, userText("并行"));

        List<SpanData> spans = otel.getSpans();
        SpanData turn = single(spans, s -> s.getName().equals("turn"));
        SpanData invoke = single(spans, s -> s.getName().startsWith("invoke_agent"));
        List<SpanData> chats =
                spans.stream().filter(s -> s.getName().startsWith("chat ")).toList();
        SpanData batch = single(
                spans,
                s -> s.getName().startsWith("execute_tool")
                        && s.getAttributes().get(AttributeKey.longKey("gen_ai.tool.call.count")) != null);
        List<SpanData> perTool = spans.stream()
                .filter(s -> s.getAttributes().get(AttributeKey.stringKey("meridian.tool.state")) != null)
                .toList();

        assertThat(turn.getParentSpanContext().isValid()).as("turn 是本轮的根").isFalse();
        assertThat(invoke.getParentSpanId())
                .as("PlatformContext(300) 在 OtelTracing(200) 之外")
                .isEqualTo(turn.getSpanId());
        assertThat(chats)
                .hasSize(2)
                .allSatisfy(c -> assertThat(c.getParentSpanId()).isEqualTo(invoke.getSpanId()));
        assertThat(batch.getParentSpanId()).isEqualTo(invoke.getSpanId());
        assertThat(perTool)
                .as("ToolSpan(50) 在 OtelTracing 的批次 span 之内，每个调用一个 span")
                .hasSize(2)
                .allSatisfy(s -> {
                    assertThat(s.getName()).isEqualTo("execute_tool echo");
                    assertThat(s.getParentSpanId()).isEqualTo(batch.getSpanId());
                    assertThat(s.getAttributes().get(AttributeKey.stringKey("meridian.tool.state")))
                            .isEqualTo("success");
                });
        assertThat(perTool)
                .extracting(s -> s.getAttributes().get(AttributeKey.stringKey("gen_ai.tool.call.id")))
                .containsExactlyInAnyOrder("call_a", "call_b");
        List<SpanData> platformSpans = spans.stream()
                .filter(s -> s.getTraceId().equals(turn.getTraceId()))
                .toList();
        assertThat(platformSpans).hasSizeGreaterThanOrEqualTo(6).allSatisfy(s -> {
            assertThat(s.getAttributes().get(AttributeKey.stringKey("meridian.tenant_id")))
                    .isEqualTo(EngineHarness.TENANT);
            assertThat(s.getAttributes().get(AttributeKey.stringKey("meridian.project_id")))
                    .isEqualTo(EngineHarness.PROJECT);
            assertThat(s.getAttributes().get(AttributeKey.stringKey("meridian.session_id")))
                    .isEqualTo(EngineHarness.SESSION);
            assertThat(s.getAttributes().get(AttributeKey.stringKey("meridian.thread_id")))
                    .isEqualTo("thr_spans");
            assertThat(s.getAttributes().get(AttributeKey.stringKey("meridian.turn_id")))
                    .isEqualTo("trn_spans");
        });
    }

    @Test
    void callsWithoutThePlatformEngineAreRejected() {
        EngineHarness harness = EngineHarness.create(ScriptedModel.builder().build());
        AgentHandle handle = harness.instances.acquire(harness.spec, TestSpecs.noneEnvironment());
        try {
            RuntimeContext bare = RuntimeContext.builder()
                    .sessionId("thr_bare")
                    .userId("prj_test:_")
                    .build();

            StepVerifier.create(handle.agent().streamEvents(userTextMessage(), bare))
                    .thenConsumeWhile(e -> true)
                    .expectErrorSatisfies(e -> assertThat(e)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("PlatformContext"))
                    .verify(EngineHarness.TIMEOUT);
        } finally {
            harness.instances.release(handle);
        }
    }

    private static List<io.agentscope.core.message.Msg> userTextMessage() {
        return List.of(new io.agentscope.core.message.UserMessage("你好"));
    }

    private static SpanData single(List<SpanData> spans, Predicate<SpanData> filter) {
        List<SpanData> matched = spans.stream().filter(filter).toList();
        assertThat(matched).hasSize(1);
        return matched.getFirst();
    }
}
