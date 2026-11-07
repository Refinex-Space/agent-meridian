package cn.refinex.meridian.engine.agentscope.testkit.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link ScriptedModel} 收到的一次请求，供测试断言。
 *
 * <p>见架构设计「测试策略」。
 *
 * @param index 第几次调用，从 0 开始
 * @param messages 发给模型的消息（含系统提示词），按 AgentScope 组装的顺序
 * @param tools 发给模型的工具 schema；没有工具时为空列表
 * @param options 生成参数；AgentScope 没有传时为空
 */
public record RecordedRequest(int index, List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {

    /** 创建记录，对列表做不可变复制。 */
    public RecordedRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
    }

    /**
     * 发给模型的工具名。
     *
     * @return 工具名集合
     */
    public Set<String> toolNames() {
        return tools.stream().map(ToolSchema::getName).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 最后一条用户消息的文本。
     *
     * @return 文本；没有用户消息时为空字符串
     */
    public String lastUserText() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.getRole() == MsgRole.USER) {
                return msg.getTextContent();
            }
        }
        return "";
    }

    /**
     * 请求中出现的全部工具结果，按出现顺序。
     *
     * @return 工具结果块
     */
    public List<ToolResultBlock> toolResults() {
        return messages.stream()
                .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                .toList();
    }

    /**
     * 第一条系统消息的文本。
     *
     * @return 系统提示词；没有时为空字符串
     */
    public String systemPrompt() {
        return messages.stream()
                .filter(m -> m.getRole() == MsgRole.SYSTEM)
                .findFirst()
                .map(Msg::getTextContent)
                .orElse("");
    }
}
