package cn.refinex.meridian.engine.agentscope.tools;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 平台工具一次执行的结果：给模型看的文本、是否出错、随结果保存进上下文的元数据（不发给模型，例如文件工具的读取状态）。
 * {@link PlatformTool} 据此决定账本的结束状态，并转成 AgentScope 的 {@link ToolResultBlock}。
 *
 * @param text 给模型看的文本；必填
 * @param error 是否出错。出错的结果仍交给模型，让它修正参数或换一种做法；账本记为 {@code failed}
 * @param metadata 结果元数据；可为空映射。随工具结果写进 {@code AgentState}，受租约约束地持久化
 */
public record ToolOutput(String text, boolean error, Map<String, Object> metadata) {

    /**
     * 创建结果。
     *
     * @throws NullPointerException 缺少文本时
     */
    public ToolOutput {
        Objects.requireNonNull(text, "text");
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /**
     * 成功的结果。
     *
     * @param text 文本
     * @return 结果
     */
    public static ToolOutput ok(String text) {
        return new ToolOutput(text, false, Map.of());
    }

    /**
     * 出错的结果。
     *
     * @param message 给模型看的错误说明：写清哪里不对、怎样修正
     * @return 结果
     */
    public static ToolOutput error(String message) {
        return new ToolOutput("Error: " + message, true, Map.of());
    }

    /**
     * 返回附带元数据的副本。
     *
     * @param key 键
     * @param value 值
     * @return 新的结果
     */
    public ToolOutput with(String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(metadata);
        copy.put(key, value);
        return new ToolOutput(text, error, copy);
    }

    /**
     * 转成 AgentScope 的工具结果。成功时状态留给 {@code ToolExecutor} 定稿，出错时标为 {@link ToolResultState#ERROR}。
     *
     * @return 工具结果
     */
    ToolResultBlock toBlock() {
        return new ToolResultBlock(
                null,
                null,
                List.of(TextBlock.builder().text(text).build()),
                metadata,
                error ? ToolResultState.ERROR : null);
    }
}
