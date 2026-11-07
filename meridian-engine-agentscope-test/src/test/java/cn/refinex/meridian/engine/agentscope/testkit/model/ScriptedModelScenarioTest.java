package cn.refinex.meridian.engine.agentscope.testkit.model;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.engine.agentscope.assembly.AgentProfile;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.StaticToolProvider;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.TestAssembly;
import cn.refinex.meridian.engine.agentscope.testkit.assembly.TestSpecs;
import cn.refinex.meridian.engine.agentscope.testkit.tool.RecordingTool;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * {@link ScriptedModel} 驱动真实装配的 {@code HarnessAgent}（T03-A05）：纯文本、单工具调用、并行工具调用、
 * 首包前错误、首包后错误，以及思考块、结构化输出与请求断言。
 */
class ScriptedModelScenarioTest {

    private static final String USER = "prj_test:_";
    private static final String SYSTEM_PROMPT = "你是测试助手，只回答被问到的内容。";

    @Test
    void plainTextIsStreamedInChunksAndEndsWithUsage() {
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.text("你好，我是助手。", 2).usage(42, 7))
                .build();
        HarnessAgent agent = agent(model, StaticToolProvider.always(RecordingTool.echo("echo")));

        Run run = run(agent, "thr_text", "你好");

        assertThat(run.error()).isNull();
        List<String> deltas = run.of(TextBlockDeltaEvent.class).stream()
                .map(TextBlockDeltaEvent::getDelta)
                .toList();
        assertThat(deltas).as("按 2 个字符一块流式返回").hasSize(4);
        assertThat(String.join("", deltas)).isEqualTo("你好，我是助手。");
        assertThat(run.types())
                .containsSubsequence(
                        AgentEventType.MODEL_CALL_START,
                        AgentEventType.TEXT_BLOCK_START,
                        AgentEventType.TEXT_BLOCK_DELTA,
                        AgentEventType.TEXT_BLOCK_END,
                        AgentEventType.MODEL_CALL_END,
                        AgentEventType.AGENT_RESULT);
        ModelCallEndEvent end = run.of(ModelCallEndEvent.class).getFirst();
        assertThat(end.getUsage().getInputTokens()).isEqualTo(42);
        assertThat(end.getUsage().getOutputTokens()).isEqualTo(7);
        Msg result = run.result();
        assertThat(result.getTextContent()).isEqualTo("你好，我是助手。");
        assertThat(result.getGenerateReason()).isEqualTo(GenerateReason.MODEL_STOP);

        RecordedRequest request = model.request(0);
        assertThat(request.lastUserText()).isEqualTo("你好");
        assertThat(request.systemPrompt()).contains(SYSTEM_PROMPT);
        assertThat(request.toolNames()).contains("echo").doesNotContain("web_search", "read_file", "execute");
        assertThat(model.remainingSteps()).isZero();
    }

    @Test
    void singleToolCallIsExecutedAndItsResultSentBackToTheModel() {
        RecordingTool echo = RecordingTool.echo("echo");
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_1", "echo", Map.of("text", "苹果")).usage(30, 5))
                .respondTo((request, r) -> r.text("工具返回了：" + firstResultText(request)))
                .build();
        HarnessAgent agent = agent(model, StaticToolProvider.always(echo));

        Run run = run(agent, "thr_tool", "查一下苹果");

        assertThat(run.error()).isNull();
        assertThat(echo.calls())
                .singleElement()
                .satisfies(call -> assertThat(call.getInput()).containsEntry("text", "苹果"));
        assertThat(run.of(ToolCallStartEvent.class))
                .extracting(ToolCallStartEvent::getToolCallId)
                .containsExactly("call_1");
        assertThat(run.of(ToolCallDeltaEvent.class).stream()
                        .map(ToolCallDeltaEvent::getDelta)
                        .collect(Collectors.joining()))
                .as("入参 JSON 分两块流式到达，拼起来是完整的入参")
                .isEqualTo("{\"text\":\"苹果\"}");
        assertThat(run.of(ToolResultEndEvent.class))
                .singleElement()
                .satisfies(e -> assertThat(e.getState()).isEqualTo(ToolResultState.SUCCESS));
        assertThat(model.request(1).toolResults()).singleElement().satisfies(block -> {
            assertThat(block.getId()).isEqualTo("call_1");
            assertThat(text(block)).isEqualTo("echo:苹果");
        });
        assertThat(run.result().getTextContent()).isEqualTo("工具返回了：echo:苹果");
        assertThat(model.remainingSteps()).isZero();
    }

    @Test
    void parallelToolCallsInOneResponseAreAllExecuted() {
        RecordingTool echo = RecordingTool.echo("echo");
        RecordingTool upper = RecordingTool.responding(
                "upper", input -> String.valueOf(input.get("text")).toUpperCase());
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_a", "echo", Map.of("text", "a"))
                        .toolCall("call_b", "upper", Map.of("text", "b")))
                .respond(r -> r.text("都完成了"))
                .build();
        HarnessAgent agent = agent(model, StaticToolProvider.always(echo, upper));

        Run run = run(agent, "thr_parallel", "并行执行");

        assertThat(run.error()).isNull();
        assertThat(echo.calls()).hasSize(1);
        assertThat(upper.calls()).hasSize(1);
        assertThat(run.of(ToolResultEndEvent.class))
                .extracting(ToolResultEndEvent::getToolCallId)
                .containsExactlyInAnyOrder("call_a", "call_b");
        Map<String, String> results = model.request(1).toolResults().stream()
                .collect(Collectors.toMap(ToolResultBlock::getId, ScriptedModelScenarioTest::text));
        assertThat(results).containsEntry("call_a", "echo:a").containsEntry("call_b", "B");
        assertThat(run.result().getTextContent()).isEqualTo("都完成了");
    }

    @Test
    void errorBeforeFirstChunkEndsTheStreamWithThatError() {
        ScriptedModel model = ScriptedModel.builder()
                .failBeforeFirstChunk(new IllegalStateException("供应商不可用"))
                .build();
        HarnessAgent agent = agent(model, StaticToolProvider.always());

        Run run = run(agent, "thr_fail_first", "你好");

        assertThat(run.error()).isInstanceOf(IllegalStateException.class).hasMessage("供应商不可用");
        assertThat(run.types()).doesNotContain(AgentEventType.TEXT_BLOCK_DELTA, AgentEventType.AGENT_RESULT);
        assertThat(model.requests()).hasSize(1);
    }

    @Test
    void errorAfterFirstChunkEndsTheStreamAfterThePartialOutput() {
        ScriptedModel model = ScriptedModel.builder()
                .failAfterChunks(r -> r.text("前半句", 1), new IllegalStateException("连接中断"))
                .build();
        HarnessAgent agent = agent(model, StaticToolProvider.always());

        Run run = run(agent, "thr_fail_after", "你好");

        assertThat(run.error()).isInstanceOf(IllegalStateException.class).hasMessage("连接中断");
        assertThat(run.of(TextBlockDeltaEvent.class).stream()
                        .map(TextBlockDeltaEvent::getDelta)
                        .collect(Collectors.joining()))
                .as("错误之前的分块已经输出")
                .isEqualTo("前半句");
        assertThat(run.types()).doesNotContain(AgentEventType.AGENT_RESULT);
    }

    @Test
    void thinkingBlocksAreStreamedBeforeTheAnswer() {
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.thinking("先想一想", 2).text("想好了"))
                .build();
        HarnessAgent agent = agent(model, StaticToolProvider.always());

        Run run = run(agent, "thr_thinking", "你好");

        assertThat(run.of(ThinkingBlockDeltaEvent.class).stream()
                        .map(ThinkingBlockDeltaEvent::getDelta)
                        .collect(Collectors.joining()))
                .isEqualTo("先想一想");
        assertThat(run.types())
                .containsSubsequence(
                        AgentEventType.THINKING_BLOCK_START,
                        AgentEventType.THINKING_BLOCK_END,
                        AgentEventType.TEXT_BLOCK_START);
        assertThat(run.result().getTextContent()).isEqualTo("想好了");
    }

    @Test
    void structuredOutputIsReturnedThroughTheGenerateResponseTool() {
        ScriptedModel model = ScriptedModel.builder()
                .respond(r -> r.structuredOutput(Map.of("answer", "四十二", "confidence", 9)))
                .build();
        HarnessAgent agent = agent(model, StaticToolProvider.always());
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("thr_structured")
                .userId(USER)
                .build();

        Msg result = agent.call(List.of(new UserMessage("答案是什么")), Verdict.class, ctx)
                .block(Duration.ofSeconds(30));

        // 2.0.4 中没有任何地方赋值 GenerateReason.STRUCTURED_OUTPUT，结构化输出完成时为 MODEL_STOP；
        // 两者在「状态机」的停止原因映射中都是 end_turn
        assertThat(result.getGenerateReason()).isEqualTo(GenerateReason.MODEL_STOP);
        assertThat(result.getStructuredData(Verdict.class)).isEqualTo(new Verdict("四十二", 9));
        assertThat(model.requests()).as("generate_response 成功后不再调用模型").hasSize(1);
        assertThat(model.request(0).toolNames()).contains("generate_response");
    }

    @Test
    void exhaustedScriptFailsLoudly() {
        ScriptedModel model = ScriptedModel.builder().build();
        HarnessAgent agent = agent(model, StaticToolProvider.always());

        Run run = run(agent, "thr_exhausted", "你好");

        assertThat(run.error()).isInstanceOf(IllegalStateException.class).hasMessageContaining("脚本已用完");
    }

    /** 结构化输出的目标类型。 */
    record Verdict(String answer, int confidence) {}

    private static HarnessAgent agent(ScriptedModel model, StaticToolProvider tools) {
        TestAssembly assembly = TestAssembly.builder(model).tools(tools).build();
        return assembly.assembler()
                .assemble(AgentProfile.main(
                        TestSpecs.agent().systemPrompt(SYSTEM_PROMPT).build(), TestSpecs.noneEnvironment()))
                .agent();
    }

    private static Run run(HarnessAgent agent, String threadId, String text) {
        RuntimeContext ctx =
                RuntimeContext.builder().sessionId(threadId).userId(USER).build();
        List<AgentEvent> events = new ArrayList<>();
        Throwable[] error = new Throwable[1];
        agent.streamEvents(List.of(new UserMessage(text)), ctx)
                .doOnNext(events::add)
                .onErrorResume(e -> {
                    error[0] = e;
                    return reactor.core.publisher.Flux.empty();
                })
                .blockLast(Duration.ofSeconds(30));
        return new Run(List.copyOf(events), error[0]);
    }

    private static String firstResultText(RecordedRequest request) {
        return text(request.toolResults().getFirst());
    }

    private static String text(ToolResultBlock block) {
        return block.getOutput().stream()
                .filter(io.agentscope.core.message.TextBlock.class::isInstance)
                .map(b -> ((io.agentscope.core.message.TextBlock) b).getText())
                .collect(Collectors.joining());
    }

    /** 一次调用的事件与结束时的错误（没有错误时为空）。 */
    private record Run(List<AgentEvent> events, Throwable error) {

        List<AgentEventType> types() {
            return events.stream().map(AgentEvent::getType).toList();
        }

        <T extends AgentEvent> List<T> of(Class<T> type) {
            return events.stream().filter(type::isInstance).map(type::cast).toList();
        }

        Msg result() {
            return of(AgentResultEvent.class).getLast().getResult();
        }
    }
}
