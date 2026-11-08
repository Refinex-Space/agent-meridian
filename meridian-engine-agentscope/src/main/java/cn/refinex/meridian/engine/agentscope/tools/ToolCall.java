package cn.refinex.meridian.engine.agentscope.tools;

import cn.refinex.meridian.common.context.PlatformContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import java.util.Map;
import java.util.Objects;

/**
 * 交给 {@link PlatformTool#execute} 的一次调用：入参、本轮的上下文、幂等键与工作区文件系统。
 *
 * @param toolUseId 工具调用 ID；必填
 * @param input 入参；必填
 * @param runtimeContext 本次调用的 {@code RuntimeContext}，文件与命令操作必须原样传给文件系统（架构设计「平台工具」）；必填
 * @param platform 平台上下文；必填
 * @param idempotencyKey 幂等键：{@code session_id:tool_use_id}，恢复确认重试时为账本中的原键。{@code non_idempotent} 工具必须
 *     把它传给下游，下游据此去重；必填
 * @param filesystem 工作区文件系统（{@code HarnessAgent.getWorkspaceManager().getFilesystem()}）；必填
 */
public record ToolCall(
        String toolUseId,
        Map<String, Object> input,
        RuntimeContext runtimeContext,
        PlatformContext platform,
        String idempotencyKey,
        AbstractFilesystem filesystem) {

    /**
     * 创建调用。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public ToolCall {
        Objects.requireNonNull(toolUseId, "toolUseId");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(runtimeContext, "runtimeContext");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(filesystem, "filesystem");
    }

    /**
     * 读取字符串参数。
     *
     * @param name 参数名
     * @return 参数值；缺省或不是字符串时为 null
     */
    public String string(String name) {
        return input.get(name) instanceof String text ? text : null;
    }

    /**
     * 读取整数参数，模型可能以数字或数字字符串给出。
     *
     * @param name 参数名
     * @param defaultValue 缺省值
     * @return 参数值
     * @throws IllegalArgumentException 不是整数时
     */
    public int integer(String name, int defaultValue) {
        Object value = input.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数 " + name + " 必须是整数：" + value, e);
        }
    }

    /**
     * 读取布尔参数。
     *
     * @param name 参数名
     * @param defaultValue 缺省值
     * @return 参数值
     */
    public boolean bool(String name, boolean defaultValue) {
        Object value = input.get(name);
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value == null
                ? defaultValue
                : Boolean.parseBoolean(String.valueOf(value).strip());
    }
}
