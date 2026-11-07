package cn.refinex.meridian.engine.spi.event;

import java.util.Objects;

/**
 * 只在流上出现、不持久化的事件：内容块的增量与心跳，见架构设计「事件映射」「SSE 协议」。
 *
 * <p>SSE 带 {@code ?event_deltas=true} 时才推送增量；同一块的完整内容以 {@link AgentOutputEvent} 持久化。
 */
public sealed interface StreamEvent extends EngineEvent {

    /**
     * {@code event_start}：一个内容块开始。
     *
     * @param blockId 块 ID；工具调用块与工具结果块取工具调用 ID；必填
     * @param kind 块的种类；必填
     * @param name 工具名，工具调用块与工具结果块时给出；其余可为空
     */
    record EventStart(String blockId, BlockKind kind, String name) implements StreamEvent {

        /**
         * 创建块开始事件。
         *
         * @throws NullPointerException 缺少块 ID 或种类时
         */
        public EventStart {
            Objects.requireNonNull(blockId, "blockId");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /**
     * {@code event_delta}：一个内容块的增量。
     *
     * @param blockId 块 ID；必填
     * @param kind 块的种类；必填
     * @param delta 增量文本：文本与思考为追加的文本，工具调用为入参 JSON 片段，工具结果为结果片段；必填
     */
    record EventDelta(String blockId, BlockKind kind, String delta) implements StreamEvent {

        /**
         * 创建块增量事件。
         *
         * @throws NullPointerException 缺少必填字段时
         */
        public EventDelta {
            Objects.requireNonNull(blockId, "blockId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(delta, "delta");
        }
    }

    /** {@code ping}：SSE 心跳，每 15 秒一次。 */
    record Ping() implements StreamEvent {}

    /** 内容块的种类。 */
    enum BlockKind {
        /** 文本块。 */
        TEXT("text"),
        /** 思考块。 */
        THINKING("thinking"),
        /** 数据块。 */
        DATA("data"),
        /** 工具调用块。 */
        TOOL_USE("tool_use"),
        /** 工具结果块。 */
        TOOL_RESULT("tool_result");

        private final String value;

        BlockKind(String value) {
            this.value = value;
        }

        /**
         * 返回事件中使用的取值。
         *
         * @return 小写下划线形式的取值
         */
        public String value() {
            return value;
        }
    }
}
