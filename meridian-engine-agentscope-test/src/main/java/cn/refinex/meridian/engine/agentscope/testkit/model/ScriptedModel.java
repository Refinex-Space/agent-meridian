package cn.refinex.meridian.engine.agentscope.testkit.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 脚本化假模型：实现 AgentScope 的 {@code Model}（经 {@link ChatModelBase}），每次被调用时按顺序取出一步脚本作为回应，
 * 并记录收到的请求（消息、工具 schema、生成参数），见架构设计「测试策略」。
 *
 * <pre>{@code
 * ScriptedModel model = ScriptedModel.builder()
 *         .respond(r -> r.toolCall("call_1", "echo", Map.of("text", "a")).usage(120, 8))
 *         .respond(r -> r.text("完成", 1).usage(160, 2))
 *         .failBeforeFirstChunk(new IllegalStateException("供应商不可用"))
 *         .build();
 * }</pre>
 *
 * <p>脚本用完后再被调用，以 {@link IllegalStateException} 结束，让测试明确失败。请求在订阅时记录，与调用顺序一致。
 *
 * <p>线程安全：可以被多个会话、并行的子智能体同时调用；每次调用取走一步，取走的顺序即调用的顺序。
 */
public final class ScriptedModel extends ChatModelBase {

    private final String modelName;
    private final Queue<Function<RecordedRequest, Flux<ChatResponse>>> steps;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    private ScriptedModel(Builder builder) {
        this.modelName = builder.modelName;
        this.steps = new ConcurrentLinkedQueue<>(builder.steps);
        if (builder.contextWindow > 0) {
            setContextWindowSize(builder.contextWindow);
        }
    }

    /**
     * 新建构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(() -> {
            int index = calls.getAndIncrement();
            RecordedRequest request = new RecordedRequest(index, messages, tools == null ? List.of() : tools, options);
            requests.add(request);
            Function<RecordedRequest, Flux<ChatResponse>> step = steps.poll();
            if (step == null) {
                return Flux.error(new IllegalStateException("ScriptedModel 的脚本已用完：第 " + (index + 1) + " 次调用没有对应的回应"));
            }
            return step.apply(request);
        });
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * 已收到的全部请求，按调用顺序。
     *
     * @return 请求记录
     */
    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    /**
     * 第 {@code index} 次请求（从 0 开始）。
     *
     * @param index 序号
     * @return 请求记录
     */
    public RecordedRequest request(int index) {
        return requests.get(index);
    }

    /**
     * 还没有被取走的脚本步数，测试结束时通常断言为 0。
     *
     * @return 剩余步数
     */
    public int remainingSteps() {
        return steps.size();
    }

    /** {@link ScriptedModel} 的构建器：每个 {@code respond*} 或 {@code fail*} 方法追加一步脚本，对应一次模型调用。 */
    public static final class Builder {

        private final List<Function<RecordedRequest, Flux<ChatResponse>>> steps = new ArrayList<>();
        private String modelName = "scripted-model";
        private int contextWindow;

        private Builder() {}

        /**
         * 模型名，出现在 AgentScope 的日志与追踪中。
         *
         * @param name 模型名
         * @return 本构建器
         */
        public Builder modelName(String name) {
            this.modelName = name;
            return this;
        }

        /**
         * 报告的上下文窗口大小，压缩据此计算阈值。不设置时为 0（未知）。
         *
         * @param tokens 上下文窗口，单位 token
         * @return 本构建器
         */
        public Builder contextWindow(int tokens) {
            this.contextWindow = tokens;
            return this;
        }

        /**
         * 追加一步固定的回应。
         *
         * @param script 编写回应的回调
         * @return 本构建器
         */
        public Builder respond(Consumer<ScriptedResponse> script) {
            return respondTo((request, response) -> script.accept(response));
        }

        /**
         * 追加一步依据请求内容编写的回应，例如回显上一个工具结果。
         *
         * @param script 编写回应的回调，参数为本次请求与待编写的回应
         * @return 本构建器
         */
        public Builder respondTo(BiConsumer<RecordedRequest, ScriptedResponse> script) {
            steps.add(request -> {
                ScriptedResponse response = new ScriptedResponse();
                script.accept(request, response);
                return response.play("scripted-" + (request.index() + 1));
            });
            return this;
        }

        /**
         * 追加一步在返回任何分块之前就失败的调用（首包前错误）。
         *
         * @param error 错误
         * @return 本构建器
         */
        public Builder failBeforeFirstChunk(Throwable error) {
            steps.add(request -> Flux.error(error));
            return this;
        }

        /**
         * 追加一步先返回若干分块、再失败的调用（首包后错误）。
         *
         * @param script 编写失败前分块的回调
         * @param error 错误
         * @return 本构建器
         */
        public Builder failAfterChunks(Consumer<ScriptedResponse> script, Throwable error) {
            return respond(script.andThen(response -> response.thenFail(error)));
        }

        /**
         * 构建模型。
         *
         * @return 脚本化模型
         */
        public ScriptedModel build() {
            return new ScriptedModel(this);
        }
    }
}
