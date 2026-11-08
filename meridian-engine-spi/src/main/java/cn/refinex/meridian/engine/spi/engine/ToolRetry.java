package cn.refinex.meridian.engine.spi.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 恢复确认选择 {@code retry} 的一个工具调用：平台用原幂等键重新执行它，把结果写进恢复说明（架构设计「待处理动作」「崩溃恢复」）。
 *
 * @param toolUseId 原工具调用 ID；必填
 * @param toolName 工具名；必填
 * @param input 原入参；必填，可为空映射
 * @param idempotencyKey 原幂等键，账本中记录的值；必填
 */
public record ToolRetry(String toolUseId, String toolName, Map<String, Object> input, String idempotencyKey) {

    /**
     * 创建重试请求。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public ToolRetry {
        Objects.requireNonNull(toolUseId, "toolUseId");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        // 入参来自模型输出，可能含 null 值
        input = Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    /**
     * 重新执行的结果。
     *
     * @param succeeded 工具是否成功
     * @param text 结果文本（失败时为错误说明），写进恢复说明的摘要；必填
     */
    public record Result(boolean succeeded, String text) {

        /**
         * 创建结果。
         *
         * @throws NullPointerException 缺少文本时
         */
        public Result {
            Objects.requireNonNull(text, "text");
        }
    }
}
