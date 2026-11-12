package cn.refinex.meridian.runtime.modelrouting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.api.model.CreateResponseRequest;
import cn.refinex.meridian.api.model.ModelResponse;
import cn.refinex.meridian.api.model.ResponseStreamEvent;
import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ChatChunk;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ChatRequest;
import cn.refinex.meridian.engine.spi.driver.ModelInvoker.ChatRole;
import cn.refinex.meridian.engine.spi.error.ProviderUnavailableException;
import cn.refinex.meridian.engine.spi.event.TokenUsage;
import cn.refinex.meridian.runtime.modelrouting.domain.ModelRoutingErrorCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link ResponsesMapper}：Responses 子集的请求翻译、不支持的参数、结束原因与流式事件序列（ADR-0050）。 */
class ResponsesMapperTest {

    private static final ResponsesMapper.Identity IDENTITY =
            new ResponsesMapper.Identity("resp_1", "msg_1", 1_700_000_000L, "mdl_1");

    @Test
    void instructionsAndInputBecomeChatMessages() {
        CreateResponseRequest request = new CreateResponseRequest(
                        "mdl_1",
                        List.of(
                                Map.of("role", "developer", "content", "简短"),
                                Map.of(
                                        "type",
                                        "message",
                                        "role",
                                        "assistant",
                                        "content",
                                        List.of(Map.of("type", "output_text", "text", "上一轮"))),
                                Map.of(
                                        "role",
                                        "user",
                                        "content",
                                        List.of(
                                                Map.of("type", "input_text", "text", "第一段"),
                                                Map.of("type", "input_text", "text", "第二段")))))
                .instructions("你是客服")
                .maxOutputTokens(64)
                .temperature(new BigDecimal("0.5"))
                .store(false);

        ChatRequest chat = ResponsesMapper.toChatRequest(request);

        assertThat(chat.messages())
                .extracting(m -> m.role() + ":" + m.content())
                .containsExactly("SYSTEM:你是客服", "SYSTEM:简短", "ASSISTANT:上一轮", "USER:第一段第二段");
        assertThat(chat.maxTokens()).isEqualTo(64);
        assertThat(chat.temperature()).isEqualTo(0.5);
        assertThat(chat.tools()).isEmpty();
        assertThat(ResponsesMapper.toChatRequest(new CreateResponseRequest("mdl_1", "你好"))
                        .messages())
                .singleElement()
                .satisfies(m -> assertThat(m.role()).isEqualTo(ChatRole.USER));
    }

    @Test
    void unsupportedParametersAreListedFieldByField() {
        CreateResponseRequest request = new CreateResponseRequest("mdl_1", "你好")
                .topP(0.9)
                .text(Map.of("format", Map.of("type", "json_object")))
                .streamOptions(Map.of("include_usage", true))
                .store(true);

        assertThatThrownBy(() -> ResponsesMapper.toChatRequest(request))
                .isInstanceOfSatisfying(ValidationException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ModelRoutingErrorCode.UNSUPPORTED_PARAMETER);
                    assertThat(e.violations())
                            .extracting(FieldViolation::field)
                            .containsExactly("text", "top_p", "stream_options", "store");
                });
    }

    @Test
    void invalidInputIsAValidationError() {
        assertThatThrownBy(() -> ResponsesMapper.toChatRequest(new CreateResponseRequest("mdl_1", "")))
                .isInstanceOfSatisfying(
                        ValidationException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> ResponsesMapper.toChatRequest(
                        new CreateResponseRequest("mdl_1", List.of(Map.of("role", "user", "content", List.of())))))
                .isInstanceOfSatisfying(
                        ValidationException.class,
                        e -> assertThat(e.violations())
                                .extracting(FieldViolation::field)
                                .containsExactly("input[0].content"));
    }

    @Test
    void lengthEndsAsIncomplete() {
        ChatCompletionMapper.Aggregate aggregate = new ChatCompletionMapper.Aggregate()
                .add(new ChatChunk("截断的", null, List.of(), null, null))
                .add(new ChatChunk(null, null, List.of(), new TokenUsage(10, 4, 2, 0, 1), "length"));

        ModelResponse response = ResponsesMapper.completed(IDENTITY, aggregate);

        assertThat(response.getStatus()).isEqualTo(ModelResponse.StatusEnum.INCOMPLETE);
        assertThat(response.getIncompleteDetails().getReason().getValue()).isEqualTo("max_output_tokens");
        assertThat(response.getOutput().getFirst().getContent().getFirst().getText())
                .isEqualTo("截断的");
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(14);
        assertThat(response.getUsage().getInputTokensDetails().getCachedTokens())
                .isEqualTo(2);
        assertThat(response.getUsage().getOutputTokensDetails().getReasoningTokens())
                .isEqualTo(1);
    }

    @Test
    void streamEventsAreNumberedAndAFailureCarriesTheReceivedText() {
        ResponsesMapper.StreamWriter writer = new ResponsesMapper.StreamWriter(IDENTITY);
        List<ResponseStreamEvent> events = new ArrayList<>(writer.start());
        events.add(writer.delta(new ChatChunk("你", null, List.of(), null, null)));
        assertThat(writer.delta(new ChatChunk(null, "思考", List.of(), null, null)))
                .as("思考内容不输出")
                .isNull();
        events.add(writer.failed(new ProviderUnavailableException("供应商断开", null)));

        assertThat(events).extracting(ResponseStreamEvent::getSequenceNumber).containsExactly(0, 1, 2, 3, 4, 5);
        ResponseStreamEvent failed = events.getLast();
        assertThat(failed.getType()).isEqualTo(ResponseStreamEvent.TypeEnum.RESPONSE_FAILED);
        assertThat(failed.getResponse().getError().getCode()).isEqualTo("model.provider_unavailable");
        assertThat(failed.getResponse()
                        .getOutput()
                        .getFirst()
                        .getContent()
                        .getFirst()
                        .getText())
                .isEqualTo("你");
    }
}
