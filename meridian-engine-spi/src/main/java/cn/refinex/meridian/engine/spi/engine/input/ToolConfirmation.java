package cn.refinex.meridian.engine.spi.engine.input;

import cn.refinex.meridian.engine.spi.spec.PermissionRule;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 对一个待确认工具调用（{@code tool_confirmation} 或 {@code plan_exit} 动作）的回应，见架构设计「待处理动作」。
 *
 * <p>内核把它翻译为 AgentScope 的 {@code ConfirmResult}，放进输入消息元数据的
 * {@code Msg.METADATA_CONFIRM_RESULTS}。AgentScope 在批准时用 {@code ConfirmResult.toolCall} 整体替换原来的
 * 工具调用，所以这里带完整的工具名与入参，而不只是调用 ID。超时未回应的动作由平台生成拒绝。
 *
 * @param toolUseId 工具调用 ID；必填
 * @param toolName 工具名；必填
 * @param input 批准后执行的入参：原始入参，或审批人修改后的入参；必填，可为空映射
 * @param approved 是否批准
 * @param denyReason 拒绝理由，作为 DENIED 工具结果的文本；可为空，表示使用默认文本。批准时不使用
 * @param rememberedRules "记住此决定"产生的规则，本轮余下的调用立即生效；之后各轮由平台作为会话级或
 *     项目级规则注入（AgentScope 不会把它写回状态）。可为空列表
 */
public record ToolConfirmation(
        String toolUseId,
        String toolName,
        Map<String, Object> input,
        boolean approved,
        String denyReason,
        List<PermissionRule> rememberedRules) {

    /**
     * 创建工具确认。
     *
     * @throws NullPointerException 缺少调用 ID、工具名或入参时
     */
    public ToolConfirmation {
        Objects.requireNonNull(toolUseId, "toolUseId");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(input, "input");
        // 入参来自 JSON，可能含 null 值，Map.copyOf 会拒绝
        input = Collections.unmodifiableMap(new LinkedHashMap<>(input));
        rememberedRules = rememberedRules == null ? List.of() : List.copyOf(rememberedRules);
    }
}
