package cn.refinex.meridian.engine.spi.engine.input;

import java.util.List;
import java.util.Objects;

/**
 * 业务方回传的自定义工具执行结果（{@code custom_tool_result} 动作的回应），见架构设计「待处理动作」。
 *
 * <p>内核把它翻译为 AgentScope 的 {@code ToolResultBlock} 放进恢复轮的输入。超时未回传时，平台以错误结果
 * 回传，内容为 {@code timeout}。
 *
 * @param toolUseId 工具调用 ID；必填
 * @param toolName 自定义工具名；必填
 * @param content 结果内容块；可为空列表
 * @param error 是否为错误结果
 */
public record CustomToolResult(String toolUseId, String toolName, List<MessageContent> content, boolean error) {

    /**
     * 创建自定义工具结果。
     *
     * @throws NullPointerException 缺少调用 ID 或工具名时
     */
    public CustomToolResult {
        Objects.requireNonNull(toolUseId, "toolUseId");
        Objects.requireNonNull(toolName, "toolName");
        content = content == null ? List.of() : List.copyOf(content);
    }
}
