package cn.refinex.meridian.runtime.modelrouting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.api.model.ChatCompletion;
import cn.refinex.meridian.api.model.ChatCompletionChunk;
import cn.refinex.meridian.api.model.ChatCompletionMessage;
import cn.refinex.meridian.api.model.CreateChatCompletionRequest;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ChatChunk;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ChatRequest;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ToolCallDelta;
import cn.refinex.meridian.engine.spi.error.BudgetExceededException;
import cn.refinex.meridian.engine.spi.event.TokenUsage;
import cn.refinex.meridian.runtime.modelrouting.api.ChatCompletionMapper.Aggregate;
import cn.refinex.meridian.runtime.modelrouting.api.ChatCompletionMapper.ChunkWriter;
import cn.refinex.meridian.runtime.modelrouting.api.ChatCompletionMapper.Completion;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link ChatCompletionMapper}：请求翻译、流式块与非流式聚合。 */
class ChatCompletionMapperTest {

    private static final Completion COMPLETION = new Completion("chatcmpl-1", 100, "mdl_x");

    @Test
    void maxCompletionTokensWinsOverMaxTokens() {
        CreateChatCompletionRequest request = new CreateChatCompletionRequest(
                        "mdl_x", List.of(new ChatCompletionMessage(ChatCompletionMessage.RoleEnum.USER).content("hi")))
                .maxTokens(100)
                .maxCompletionTokens(50)
                .temperature(new java.math.BigDecimal("0.5"));

        ChatRequest chat = ChatCompletionMapper.toChatRequest(request);

        assertThat(chat.maxTokens()).isEqualTo(50);
        assertThat(chat.temperature()).isEqualTo(0.5);
    }

    @Test
    void toolCallsOnNonAssistantMessagesAreRejected() {
        CreateChatCompletionRequest request = new CreateChatCompletionRequest(
                "mdl_x",
                List.of(new ChatCompletionMessage(ChatCompletionMessage.RoleEnum.USER)
                        .content("hi")
                        .toolCalls(List.of(new cn.refinex.meridian.api.model.ChatCompletionMessageToolCall(
                                "c1",
                                cn.refinex.meridian.api.model.ChatCompletionMessageToolCall.TypeEnum.FUNCTION,
                                new cn.refinex.meridian.api.model.ChatCompletionFunctionCall("f", "{}"))))));

        assertThatThrownBy(() -> ChatCompletionMapper.toChatRequest(request))
                .isInstanceOfSatisfying(
                        ValidationException.class,
                        e -> assertThat(e.violations())
                                .singleElement()
                                .satisfies(v -> assertThat(v.field()).isEqualTo("messages.0.tool_calls")));
    }

    @Test
    void theFirstChunkCarriesTheRoleAndUsageIsHeldForTheLastChunk() {
        ChunkWriter writer = new ChunkWriter(COMPLETION);

        ChatCompletionChunk first = writer.delta(new ChatChunk("你", null, List.of(), null, null));
        ChatCompletionChunk second = writer.delta(new ChatChunk("好", null, List.of(), null, "stop"));
        ChatCompletionChunk usageOnly = writer.delta(new ChatChunk(null, null, List.of(), TokenUsage.of(3, 2), null));

        assertThat(first.getChoices().getFirst().getDelta().getRole()).isNotNull();
        assertThat(second.getChoices().getFirst().getDelta().getRole()).isNull();
        assertThat(second.getChoices().getFirst().getFinishReason()).isEqualTo("stop");
        assertThat(usageOnly).as("只带用量的块不单独输出").isNull();
        ChatCompletionChunk usage = writer.usageChunk();
        assertThat(usage.getChoices()).isEmpty();
        assertThat(usage.getUsage().getTotalTokens()).isEqualTo(5);
    }

    @Test
    void aggregationMergesTextReasoningAndToolCallsByIndex() {
        ChatCompletion completion = new Aggregate()
                .add(new ChatChunk(null, "想一想", List.of(), null, null))
                .add(new ChatChunk(null, null, List.of(new ToolCallDelta(0, "c1", "lookup", "{\"id\":")), null, null))
                .add(new ChatChunk(null, null, List.of(new ToolCallDelta(1, "c2", "refund", "{}")), null, null))
                .add(new ChatChunk(null, null, List.of(new ToolCallDelta(0, null, null, "1}")), null, "tool_calls"))
                .add(new ChatChunk(null, null, List.of(), new TokenUsage(10, 4, 2, 0, 1), null))
                .toCompletion(COMPLETION);

        var message = completion.getChoices().getFirst().getMessage();
        assertThat(message.getContent()).as("只有工具调用时内容为空").isNull();
        assertThat(message.getReasoningContent()).isEqualTo("想一想");
        assertThat(message.getToolCalls())
                .extracting(call -> call.getId() + ":" + call.getFunction().getName() + ":"
                        + call.getFunction().getArguments())
                .containsExactly("c1:lookup:{\"id\":1}", "c2:refund:{}");
        assertThat(completion.getChoices().getFirst().getFinishReason()).isEqualTo("tool_calls");
        assertThat(completion.getUsage().getPromptTokensDetails().getCachedTokens())
                .isEqualTo(2);
        assertThat(completion.getUsage().getCompletionTokensDetails().getReasoningTokens())
                .isEqualTo(1);
    }

    @Test
    void errorFramesCarryThePlatformCodeOnly() {
        assertThat(ChatCompletionMapper.errorFrame(new BudgetExceededException("项目预算不足")))
                .isEqualTo(Map.of(
                        "error",
                        Map.of("message", "项目预算不足", "type", "model.budget_exceeded", "code", "model.budget_exceeded")));
        assertThat(ChatCompletionMapper.errorFrame(new IllegalStateException("内部细节"))
                        .toString())
                .doesNotContain("内部细节");
    }

    @Test
    void embeddingInputsAcceptTextOrTextArraysOnly() {
        assertThat(ModelsApiController.inputs("a")).containsExactly("a");
        assertThat(ModelsApiController.inputs(List.of("a", "b"))).containsExactly("a", "b");
        assertThatThrownBy(() -> ModelsApiController.inputs(List.of("a", 1))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ModelsApiController.inputs("")).isInstanceOf(ValidationException.class);
        assertThat(ModelsApiController.base64(new float[] {1.0f})).isEqualTo("AACAPw==");
    }
}
