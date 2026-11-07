package cn.refinex.meridian.engine.agentscope.testkit.model;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Flux;

/**
 * {@link ScriptedModel} 一次调用的回应：按添加顺序流式返回的分块，最后一块带用量与结束原因，可以在分块之后失败。
 *
 * <p>分块的形状与真实供应商一致，AgentScope 据此产生相同的事件序列：
 * <ul>
 *   <li>文本、思考按指定大小切成多个分块，每块一个 {@code TEXT_BLOCK_DELTA} 或 {@code THINKING_BLOCK_DELTA}。
 *   <li>工具调用的入参 JSON 切成两个分块，都带调用 ID 与工具名，各产生一个 {@code TOOL_CALL_DELTA}。
 *   <li>结构化输出是对 {@code generate_response} 的工具调用（模型不支持原生结构化输出时 AgentScope 的做法），
 *       入参形如 {@code {"response": {...}}}，与 2.0.4 中该工具的 schema 一致。
 * </ul>
 *
 * <p>线程安全：构建完成后只读。
 *
 * <p>见架构设计「测试策略」。
 */
public final class ScriptedResponse {

    /** AgentScope 2.0.4 的 {@code generate_response} 工具把结构化数据放在这个字段下。 */
    private static final String STRUCTURED_OUTPUT_FIELD = "response";

    private final List<List<ContentBlock>> chunks = new ArrayList<>();
    private ChatUsage usage;
    private String finishReason;
    private Throwable failure;
    private int structuredOutputs;

    ScriptedResponse() {}

    /**
     * 追加一段文本，作为一个分块。
     *
     * @param text 文本
     * @return 本回应
     */
    public ScriptedResponse text(String text) {
        return text(text, Integer.MAX_VALUE);
    }

    /**
     * 追加一段文本，按码点切成每块 {@code chunkSize} 个字符的多个分块。
     *
     * @param text 文本
     * @param chunkSize 每块的字符数，大于 0
     * @return 本回应
     */
    public ScriptedResponse text(String text, int chunkSize) {
        for (String piece : split(text, chunkSize)) {
            chunks.add(List.of(TextBlock.builder().text(piece).build()));
        }
        return this;
    }

    /**
     * 追加一段思考内容，作为一个分块。
     *
     * @param thinking 思考内容
     * @return 本回应
     */
    public ScriptedResponse thinking(String thinking) {
        return thinking(thinking, Integer.MAX_VALUE);
    }

    /**
     * 追加一段思考内容，按码点切成多个分块。
     *
     * @param thinking 思考内容
     * @param chunkSize 每块的字符数，大于 0
     * @return 本回应
     */
    public ScriptedResponse thinking(String thinking, int chunkSize) {
        for (String piece : split(thinking, chunkSize)) {
            chunks.add(List.of(ThinkingBlock.builder().thinking(piece).build()));
        }
        return this;
    }

    /**
     * 追加一个工具调用。同一回应中的多个工具调用就是并行工具调用。
     *
     * @param id 工具调用 ID
     * @param name 工具名
     * @param input 入参
     * @return 本回应
     */
    public ScriptedResponse toolCall(String id, String name, Map<String, Object> input) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        String json = JsonUtils.getJsonCodec().toJson(input);
        int half = Math.max(1, json.length() / 2);
        for (String fragment : List.of(json.substring(0, half), json.substring(half))) {
            if (!fragment.isEmpty()) {
                chunks.add(List.of(new ToolUseBlock(id, name, Map.of(), fragment, null)));
            }
        }
        return this;
    }

    /**
     * 追加一个结构化输出，即对 {@code generate_response} 的工具调用，调用 ID 自动生成。
     *
     * @param data 结构化数据
     * @return 本回应
     */
    public ScriptedResponse structuredOutput(Map<String, Object> data) {
        structuredOutputs++;
        return toolCall(
                "call_structured_" + structuredOutputs,
                ReActAgent.STRUCTURED_OUTPUT_TOOL_NAME,
                Map.of(STRUCTURED_OUTPUT_FIELD, data));
    }

    /**
     * 追加一个原样的分块，用于构造交错到达的并行工具调用等特殊顺序。
     *
     * @param blocks 分块中的内容块
     * @return 本回应
     */
    public ScriptedResponse rawChunk(ContentBlock... blocks) {
        chunks.add(List.of(blocks));
        return this;
    }

    /**
     * 在最后一块上带用量。
     *
     * @param inputTokens 输入 token 数
     * @param outputTokens 输出 token 数
     * @return 本回应
     */
    public ScriptedResponse usage(int inputTokens, int outputTokens) {
        return usage(ChatUsage.builder()
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .build());
    }

    /**
     * 在最后一块上带用量。
     *
     * @param chatUsage 用量
     * @return 本回应
     */
    public ScriptedResponse usage(ChatUsage chatUsage) {
        this.usage = chatUsage;
        return this;
    }

    /**
     * 指定结束原因；不指定时有工具调用为 {@code tool_calls}，否则为 {@code stop}。
     *
     * @param reason 结束原因
     * @return 本回应
     */
    public ScriptedResponse finishReason(String reason) {
        this.finishReason = reason;
        return this;
    }

    /**
     * 在全部分块之后以错误结束（首包后错误）。没有任何分块时等同于首包前错误。
     *
     * @param error 错误
     * @return 本回应
     */
    public ScriptedResponse thenFail(Throwable error) {
        this.failure = Objects.requireNonNull(error, "error");
        return this;
    }

    /** 生成本回应的分块流。 */
    Flux<ChatResponse> play(String responseId) {
        List<ChatResponse> responses = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            boolean last = i == chunks.size() - 1;
            responses.add(new ChatResponse(
                    responseId, chunks.get(i), last ? usage : null, null, last ? effectiveFinishReason() : null));
        }
        if (responses.isEmpty() && usage != null) {
            responses.add(new ChatResponse(responseId, List.of(), usage, null, effectiveFinishReason()));
        }
        Flux<ChatResponse> flux = Flux.fromIterable(responses);
        return failure == null ? flux : flux.concatWith(Flux.error(failure));
    }

    private String effectiveFinishReason() {
        if (finishReason != null) {
            return finishReason;
        }
        boolean hasToolCall = chunks.stream().flatMap(List::stream).anyMatch(ToolUseBlock.class::isInstance);
        return hasToolCall ? "tool_calls" : "stop";
    }

    private static List<String> split(String text, int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须大于 0：" + chunkSize);
        }
        List<String> pieces = new ArrayList<>();
        int[] codePoints = text.codePoints().toArray();
        for (int start = 0; start < codePoints.length; start += chunkSize) {
            int end = (int) Math.min((long) start + chunkSize, codePoints.length);
            pieces.add(new String(codePoints, start, end - start));
        }
        if (pieces.isEmpty()) {
            pieces.add("");
        }
        return pieces;
    }
}
