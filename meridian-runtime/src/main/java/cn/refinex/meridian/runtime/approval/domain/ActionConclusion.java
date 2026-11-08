package cn.refinex.meridian.runtime.approval.domain;

import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import cn.refinex.meridian.engine.spi.event.PendingAction;
import cn.refinex.meridian.engine.spi.event.UserEvent;
import cn.refinex.meridian.engine.spi.event.UserEvent.RecoveryDecision;
import cn.refinex.meridian.engine.spi.event.UserEvent.RememberScope;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一个待处理动作的结论（{@code pending_actions.response}）：调用方的回应，或平台按超时、中断、无人值守生成的结论，见架构设计
 * 「待处理动作」。生成恢复轮时据此构造输入。按动作类型只填对应的字段，其余为空。
 *
 * @param origin 结论的来源；必填
 * @param approved {@code tool_confirmation}、{@code plan_exit}：是否批准
 * @param updatedInput 批准时审批人修改后的入参；为空表示按原入参执行
 * @param denyReason 拒绝理由，作为 DENIED 工具结果的文本；为空表示使用 AgentScope 的默认文本
 * @param remember "记住此决定"的作用域；确认类动作必填，不记住时为 {@link RememberScope#NONE}
 * @param content {@code custom_tool_result}：结果内容块
 * @param error {@code custom_tool_result}：是否为错误结果
 * @param decision {@code recovery_confirmation}：重试或跳过
 */
public record ActionConclusion(
        ConclusionOrigin origin,
        Boolean approved,
        Map<String, Object> updatedInput,
        String denyReason,
        RememberScope remember,
        List<MessageContent> content,
        Boolean error,
        RecoveryDecision decision) {

    /** 超时的自定义工具回传给智能体的内容（架构设计「待处理动作」）。 */
    public static final String TIMEOUT_RESULT = "timeout";

    /** 被中断的自定义工具回传给智能体的内容。 */
    public static final String INTERRUPTED_RESULT = "interrupted";

    /**
     * 创建结论。
     *
     * @throws NullPointerException 缺少来源时
     */
    public ActionConclusion {
        Objects.requireNonNull(origin, "origin");
        updatedInput = updatedInput == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(updatedInput));
        content = content == null ? null : List.copyOf(content);
    }

    /**
     * 调用方回应事件给出的结论。
     *
     * @param response 回应事件
     * @return 结论
     * @throws IllegalArgumentException 不是回应事件时
     */
    public static ActionConclusion of(UserEvent response) {
        return switch (response) {
            case UserEvent.ToolConfirmation c ->
                new ActionConclusion(
                        ConclusionOrigin.CALLER,
                        c.approved(),
                        c.updatedInput(),
                        c.denyReason(),
                        c.rememberScope(),
                        null,
                        null,
                        null);
            case UserEvent.CustomToolResult r ->
                new ActionConclusion(ConclusionOrigin.CALLER, null, null, null, null, r.content(), r.error(), null);
            case UserEvent.RecoveryConfirmation r ->
                new ActionConclusion(ConclusionOrigin.CALLER, null, null, null, null, null, null, r.decision());
            case UserEvent.Message _, UserEvent.Interrupt _ ->
                throw new IllegalArgumentException("不是回应事件：" + response.type().value());
        };
    }

    /**
     * 平台生成的结论：超时、中断或无人值守时，按动作类型一律视为拒绝（架构设计「待处理动作」）。
     *
     * @param type 动作类型
     * @param origin 来源，不能是 {@link ConclusionOrigin#CALLER}
     * @return 结论
     */
    public static ActionConclusion declined(PendingAction.Type type, ConclusionOrigin origin) {
        if (origin == ConclusionOrigin.CALLER) {
            throw new IllegalArgumentException("调用方的回应用 of(UserEvent) 构造");
        }
        return switch (type) {
            case TOOL_CONFIRMATION, PLAN_EXIT ->
                new ActionConclusion(
                        origin, false, null, denyReason(type, origin), RememberScope.NONE, null, null, null);
            case CUSTOM_TOOL_RESULT -> {
                String text = origin == ConclusionOrigin.TIMEOUT ? TIMEOUT_RESULT : INTERRUPTED_RESULT;
                yield new ActionConclusion(
                        origin, null, null, null, null, List.of(new MessageContent.Text(text)), true, null);
            }
            case RECOVERY_CONFIRMATION ->
                new ActionConclusion(origin, null, null, null, null, null, null, RecoveryDecision.SKIP);
        };
    }

    /**
     * 确认类结论是否为批准。
     *
     * @return 批准时为 true
     */
    public boolean isApproved() {
        return Boolean.TRUE.equals(approved);
    }

    /** 平台生成的拒绝写给模型看的理由。 */
    private static String denyReason(PendingAction.Type type, ConclusionOrigin origin) {
        return switch (origin) {
            case TIMEOUT -> type == PendingAction.Type.PLAN_EXIT ? "审批超时，视为拒绝，保持计划模式。" : "审批超时，视为拒绝，工具没有执行。";
            case INTERRUPT -> "用户中断了本轮，工具没有执行。";
            case UNATTENDED -> "无人值守的会话无法审批，平台已自动拒绝，工具没有执行。";
            case CALLER -> throw new IllegalArgumentException("调用方的拒绝理由由回应给出");
        };
    }
}
