package cn.refinex.meridian.engine.spi.event;

import java.util.Objects;

/**
 * span 域的事件：模型请求的起止，用于时间轴展示与排查，见架构设计「事件映射」。
 */
public sealed interface SpanEvent extends EngineEvent {

    /**
     * {@code span.model_request_start}：一次模型请求开始。
     *
     * @param requestId 本次请求的 ID，起止事件相同；必填
     * @param modelId 模型 ID（{@code mdl_…}）；必填
     * @param context 本次请求的上下文构成摘要，不含正文（架构设计「上下文管理」）；可为空，表示没有统计
     */
    record ModelRequestStart(String requestId, String modelId, ContextComposition context) implements SpanEvent {

        /**
         * 创建请求开始事件。
         *
         * @throws NullPointerException 缺少请求 ID 或模型 ID 时
         */
        public ModelRequestStart {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(modelId, "modelId");
        }

        /**
         * 不带上下文构成的请求开始事件。
         *
         * @param requestId 本次请求的 ID
         * @param modelId 模型 ID
         */
        public ModelRequestStart(String requestId, String modelId) {
            this(requestId, modelId, null);
        }
    }

    /**
     * {@code span.model_request_end}：一次模型请求结束。
     *
     * @param requestId 本次请求的 ID；必填
     * @param modelId 模型 ID；必填
     * @param usage token 用量；可为空，表示请求失败、供应商没有报告用量
     */
    record ModelRequestEnd(String requestId, String modelId, TokenUsage usage) implements SpanEvent {

        /**
         * 创建请求结束事件。
         *
         * @throws NullPointerException 缺少请求 ID 或模型 ID 时
         */
        public ModelRequestEnd {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(modelId, "modelId");
        }
    }
}
