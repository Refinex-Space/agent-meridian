package cn.refinex.meridian.engine.agentscope.model;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.engine.agentscope.testkit.model.InMemoryModelGateway;
import cn.refinex.meridian.engine.agentscope.testkit.model.RecordedRequest;
import cn.refinex.meridian.engine.agentscope.testkit.model.ScriptedModel;
import cn.refinex.meridian.engine.agentscope.testkit.model.StaticProviderClientFactory;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ChatChunk;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ChatMessage;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ChatRequest;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ChatRole;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ToolCall;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ToolCallDelta;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ToolDefinition;
import cn.refinex.meridian.engine.spi.error.BudgetExceededException;
import cn.refinex.meridian.engine.spi.event.TokenUsage;
import cn.refinex.meridian.engine.spi.port.ModelGateway.CallPurpose;
import cn.refinex.meridian.engine.spi.port.ProviderRetryPolicy;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** {@link RoutedModelInvoker}：直通调用经 {@link RoutedModel} 计量，消息与增量的翻译。 */
class RoutedModelInvokerTest {

    private static final String MODEL = "mdl_direct";
    private static final PlatformContext PLATFORM = PlatformContext.builder()
            .tenantId("ten_t")
            .projectId("prj_t")
            .apiKeyId("key_t")
            .build();
    private static final ProviderRetryPolicy FAST_RETRY =
            new ProviderRetryPolicy(2, Duration.ofMillis(1), Duration.ofSeconds(1));

    private final InMemoryModelGateway gateway = new InMemoryModelGateway().route(MODEL, "up-direct", 32_000, 800);
    private final StaticProviderClientFactory providers = new StaticProviderClientFactory();
    private final RoutedModelInvoker invoker =
            new RoutedModelInvoker(gateway, new ProviderClients(List.of(providers)), FAST_RETRY);

    @Test
    void textCallIsMeteredWithoutAgentVersionAndHonoursCallerOptions() {
        ScriptedModel upstream = ScriptedModel.builder()
                .respond(r -> r.text("你好，世界", 2).usage(12, 5))
                .build();
        providers.register("up-direct", upstream);

        List<ChatChunk> chunks = collect(
                new ChatRequest(MODEL, List.of(new ChatMessage(ChatRole.USER, "打个招呼", null, null)), null, 64, 0.3));

        assertThat(chunks.stream().map(ChatChunk::textDelta).filter(t -> t != null))
                .containsExactly("你好", "，世", "界");
        ChatChunk last = chunks.getLast();
        assertThat(last.usage()).isEqualTo(TokenUsage.of(12, 5));
        assertThat(last.finishReason()).isEqualTo("stop");
        assertThat(gateway.settlements()).singleElement().satisfies(record -> {
            assertThat(record.agentVersionId()).as("直通调用不经智能体").isNull();
            assertThat(record.purpose()).isEqualTo(CallPurpose.REASONING);
            assertThat(record.usage()).isEqualTo(TokenUsage.of(12, 5));
        });
        assertThat(gateway.rateLimitRequests()).as("预扣按调用方的 max_tokens").allMatch(tokens -> tokens < 800);
        assertThat(providers.callOptions().getFirst().getMaxTokens()).isEqualTo(64);
        assertThat(providers.callOptions().getFirst().getTemperature()).isEqualTo(0.3);
    }

    @Test
    void conversationWithToolsIsTranslatedToAgentScopeMessages() {
        ScriptedModel upstream =
                ScriptedModel.builder().respond(r -> r.text("北京晴").usage(40, 3)).build();
        providers.register("up-direct", upstream);
        ToolDefinition weather = new ToolDefinition(
                "get_weather",
                "查询天气",
                Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string"))));

        collect(new ChatRequest(
                MODEL,
                List.of(
                        new ChatMessage(ChatRole.SYSTEM, "你是助手", null, null),
                        new ChatMessage(ChatRole.USER, "北京天气？", null, null),
                        new ChatMessage(
                                ChatRole.ASSISTANT,
                                null,
                                List.of(new ToolCall("call_1", "get_weather", "{\"city\":\"北京\"}")),
                                null),
                        new ChatMessage(ChatRole.TOOL, "晴，20 度", null, "call_1")),
                List.of(weather),
                null,
                null));

        RecordedRequest request = upstream.requests().getFirst();
        assertThat(request.toolNames()).containsExactly("get_weather");
        assertThat(request.messages())
                .extracting(m -> m.getRole())
                .containsExactly(MsgRole.SYSTEM, MsgRole.USER, MsgRole.ASSISTANT, MsgRole.TOOL);
        ToolUseBlock call = request.messages().get(2).getFirstContentBlock(ToolUseBlock.class);
        assertThat(call.getId()).isEqualTo("call_1");
        assertThat(call.getInput()).containsEntry("city", "北京");
        assertThat(call.getContent()).isEqualTo("{\"city\":\"北京\"}");
        ToolResultBlock result = request.messages().get(3).getFirstContentBlock(ToolResultBlock.class);
        assertThat(result.getId()).isEqualTo("call_1");
        assertThat(((TextBlock) result.getOutput().getFirst()).getText()).isEqualTo("晴，20 度");
    }

    @Test
    void streamedToolCallFragmentsKeepTheirIndex() {
        // 2.0.4 OpenAI 兼容客户端的流式格式：带工具名的首块，之后是工具名为 __fragment__、ID 为空的参数片段
        ScriptedModel upstream = ScriptedModel.builder()
                .respond(r -> r.rawChunk(new ToolUseBlock("call_a", "lookup", Map.of(), "{\"id\":", null))
                        .rawChunk(new ToolUseBlock("", RoutedModelInvoker.FRAGMENT_NAME, Map.of(), "\"o1\"}", null))
                        .rawChunk(new ToolUseBlock("call_b", "refund", Map.of(), "", null))
                        .rawChunk(new ToolUseBlock("", RoutedModelInvoker.FRAGMENT_NAME, Map.of(), "{}", null))
                        .usage(20, 9))
                .build();
        providers.register("up-direct", upstream);

        List<ToolCallDelta> deltas = collect(new ChatRequest(
                        MODEL, List.of(new ChatMessage(ChatRole.USER, "查订单再退款", null, null)), null, null, null))
                .stream()
                .flatMap(chunk -> chunk.toolCalls().stream())
                .toList();

        assertThat(deltas)
                .containsExactly(
                        new ToolCallDelta(0, "call_a", "lookup", "{\"id\":"),
                        new ToolCallDelta(0, null, null, "\"o1\"}"),
                        new ToolCallDelta(1, "call_b", "refund", ""),
                        new ToolCallDelta(1, null, null, "{}"));
    }

    @Test
    void repeatedIdFragmentsBelongToTheSameCall() {
        ScriptedModel upstream = ScriptedModel.builder()
                .respond(r -> r.toolCall("call_x", "lookup", Map.of("id", "o1")).usage(10, 4))
                .build();
        providers.register("up-direct", upstream);

        List<ToolCallDelta> deltas = collect(new ChatRequest(
                        MODEL, List.of(new ChatMessage(ChatRole.USER, "查", null, null)), null, null, null))
                .stream()
                .flatMap(chunk -> chunk.toolCalls().stream())
                .toList();

        assertThat(deltas).extracting(ToolCallDelta::index).containsOnly(0);
        assertThat(deltas.getFirst().name()).isEqualTo("lookup");
        assertThat(String.join(
                        "", deltas.stream().map(ToolCallDelta::argumentsDelta).toList()))
                .isEqualTo("{\"id\":\"o1\"}");
    }

    @Test
    void budgetErrorsPropagateWithoutCallingTheProvider() {
        ScriptedModel upstream =
                ScriptedModel.builder().respond(r -> r.text("不会被调用")).build();
        providers.register("up-direct", upstream);
        gateway.budgetLimit(10L);

        StepVerifier.create(invoker.stream(new ChatRequest(
                                MODEL, List.of(new ChatMessage(ChatRole.USER, "你好", null, null)), null, null, null))
                        .contextWrite(PlatformContexts.write(PLATFORM)))
                .expectError(BudgetExceededException.class)
                .verify();
        assertThat(upstream.requests()).isEmpty();
    }

    private List<ChatChunk> collect(ChatRequest request) {
        Flux<ChatChunk> chunks = invoker.stream(request).contextWrite(PlatformContexts.write(PLATFORM));
        List<ChatChunk> result = chunks.collectList().block(Duration.ofSeconds(10));
        assertThat(result).isNotNull();
        return result;
    }
}
