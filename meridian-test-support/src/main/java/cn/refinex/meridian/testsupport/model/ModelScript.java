package cn.refinex.meridian.testsupport.model;

import java.util.List;
import java.util.Objects;

/**
 * 一个脚本：按顺序的响应序列。第 n 次调用（以选中脚本的用户消息之后的 assistant 消息数计）返回第 n 步。
 *
 * @param name 脚本名，用户消息以 {@code #script:<名称>} 开头时选中它；必填
 * @param description 说明；可为空
 * @param steps 响应序列，至少一步
 */
public record ModelScript(String name, String description, List<Step> steps) {

    /**
     * 创建脚本。
     *
     * @throws NullPointerException 缺少脚本名时
     * @throws IllegalArgumentException 没有步骤时
     */
    public ModelScript {
        Objects.requireNonNull(name, "name");
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("脚本至少要有一步：" + name);
        }
    }

    /**
     * 一步响应。文本、思考与工具调用可以同时出现；{@code error} 出现时其余字段不生效。
     *
     * @param text 回答文本；可为空
     * @param reasoning 思考内容（{@code reasoning_content}）；可为空
     * @param toolCalls 工具调用；可为空列表
     * @param chunkSize 流式时文本每块的字符数，大于 0
     * @param delayMillis 流式时相邻两块之间的延迟，不小于 0
     * @param usage 报告的用量；为空时按字符数估算
     * @param finishReason 结束原因；为空时有工具调用为 {@code tool_calls}，否则为 {@code stop}
     * @param error 首包之前返回的 HTTP 错误；可为空
     * @param failAfterChunks 流式时发出这么多块之后断开连接（首包之后断流）；为空表示正常结束
     */
    public record Step(
            String text,
            String reasoning,
            List<ToolCall> toolCalls,
            int chunkSize,
            long delayMillis,
            Usage usage,
            String finishReason,
            HttpError error,
            Integer failAfterChunks) {

        /** 默认的流式块大小（字符）。 */
        public static final int DEFAULT_CHUNK_SIZE = 8;

        /**
         * 创建步骤。
         *
         * @throws IllegalArgumentException 块大小不大于 0、延迟为负或断流块数为负时
         */
        public Step {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            if (chunkSize < 1) {
                throw new IllegalArgumentException("chunk_size 必须大于 0：" + chunkSize);
            }
            if (delayMillis < 0) {
                throw new IllegalArgumentException("delay_ms 不能为负：" + delayMillis);
            }
            if (failAfterChunks != null && failAfterChunks < 0) {
                throw new IllegalArgumentException("fail_after_chunks 不能为负：" + failAfterChunks);
            }
        }

        /**
         * 只有文本的步骤。
         *
         * @param text 文本
         * @return 步骤
         */
        public static Step text(String text) {
            return new Step(text, null, List.of(), DEFAULT_CHUNK_SIZE, 0, null, null, null, null);
        }

        /**
         * 结束原因：显式给出的，或按是否有工具调用推断。
         *
         * @return 结束原因
         */
        public String effectiveFinishReason() {
            if (finishReason != null) {
                return finishReason;
            }
            return toolCalls.isEmpty() ? "stop" : "tool_calls";
        }
    }

    /**
     * 一个工具调用。
     *
     * @param id 调用 ID；为空时由服务按脚本、步骤与选中消息的位置生成确定的 ID
     * @param name 工具名；必填
     * @param arguments 入参 JSON 原文；必填
     */
    public record ToolCall(String id, String name, String arguments) {

        /**
         * 创建工具调用。
         *
         * @throws NullPointerException 缺少工具名或入参时
         */
        public ToolCall {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(arguments, "arguments");
        }
    }

    /**
     * 报告的用量。
     *
     * @param promptTokens 输入 token
     * @param completionTokens 输出 token
     */
    public record Usage(int promptTokens, int completionTokens) {}

    /**
     * 首包之前的 HTTP 错误。
     *
     * @param status 状态码，例如 503、429、401
     * @param message 错误说明；可为空
     * @param retryAfterSeconds {@code Retry-After} 秒数；可为空
     */
    public record HttpError(int status, String message, Integer retryAfterSeconds) {}
}
