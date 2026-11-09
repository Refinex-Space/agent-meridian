package cn.refinex.meridian.runtime.delivery.domain;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.ConflictException;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.engine.spi.event.PendingAction;
import cn.refinex.meridian.engine.spi.event.UserEvent;
import cn.refinex.meridian.runtime.approval.domain.StoredPendingAction;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 调用方发送的一批用户事件与会话当前状态的组合规则（架构设计「消息投递」「待处理动作」）：
 * <ul>
 *   <li>回应事件必须对应会话中一个仍在等待的动作，且事件类型与动作类型对应：{@code user.tool_confirmation} 回应
 *       {@code tool_confirmation} 与 {@code plan_exit}，{@code user.custom_tool_result} 回应 {@code custom_tool_result}，
 *       {@code user.recovery_confirmation} 回应 {@code recovery_confirmation}；同一批里不能回应同一个动作两次。
 *   <li>跟在 {@code user.interrupt} 后面的消息一律按 queue 投递：AgentScope 忽略随中断传入的消息，steer 来不及注入当前轮。
 *   <li>同一批里不能既有 {@code user.interrupt} 又有回应事件：等待回应时中断会把全部待处理动作视为拒绝，与同批的回应相互矛盾
 *       （曾在回应时因动作已有结论抛出 500 并回滚整批，R1 并发审查）。
 * </ul>
 * 会话已终止时不接受任何事件，由调用方先检查。纯函数，线程安全。
 */
public final class EventAdmission {

    private EventAdmission() {}

    /**
     * 一批事件中被回应的动作 ID，按出现顺序。
     *
     * @param events 用户事件
     * @return 动作 ID
     * @throws ValidationException 同一批里有两个事件回应同一个动作，或既有中断又有回应事件时
     */
    public static Set<String> respondedActionIds(List<UserEvent> events) {
        if (events.stream().anyMatch(UserEvent.Interrupt.class::isInstance)
                && events.stream().anyMatch(event -> actionIdOf(event) != null)) {
            throw new ValidationException(
                    CommonErrorCode.VALIDATION_FAILED,
                    "同一批事件不能既中断又回应待处理动作",
                    List.of(new FieldViolation("events", "user.interrupt 与回应事件请分两次发送")));
        }
        Set<String> ids = new LinkedHashSet<>();
        for (UserEvent event : events) {
            String actionId = actionIdOf(event);
            if (actionId != null && !ids.add(actionId)) {
                throw new ValidationException(
                        CommonErrorCode.VALIDATION_FAILED,
                        "同一批事件不能重复回应同一个待处理动作：" + actionId,
                        List.of(new FieldViolation("events", "重复回应待处理动作 " + actionId)));
            }
        }
        return ids;
    }

    /**
     * 校验每个回应事件都对应一个仍在等待、类型匹配的动作。
     *
     * @param events 用户事件
     * @param actions 会话中被回应的动作，按动作 ID 索引；不存在的动作不在其中
     * @throws ConflictException 组合不合法时，错误码见 {@link EventErrorCode}
     */
    public static void checkResponses(List<UserEvent> events, Map<String, StoredPendingAction> actions) {
        for (UserEvent event : events) {
            String actionId = actionIdOf(event);
            if (actionId == null) {
                continue;
            }
            StoredPendingAction action = actions.get(actionId);
            if (action == null) {
                throw new ConflictException(EventErrorCode.ACTION_NOT_FOUND, "会话中没有待处理动作：" + actionId);
            }
            if (!accepts(event, action.type())) {
                throw new ConflictException(
                        EventErrorCode.ACTION_TYPE_MISMATCH,
                        event.type().value() + " 不能回应 " + action.type().value() + " 类型的动作：" + actionId);
            }
            if (!action.isPending()) {
                throw new ConflictException(
                        EventErrorCode.ACTION_NOT_PENDING, "待处理动作已" + statusText(action) + "：" + actionId);
            }
        }
    }

    /**
     * 每个事件的实际投递方式：{@code user.message} 取其 {@code delivery}，跟在 {@code user.interrupt} 后面的降级为 queue；
     * 其他事件为空。
     *
     * @param events 用户事件
     * @return 与 {@code events} 一一对应的投递方式，非消息事件为 {@code null}
     */
    public static List<UserEvent.Delivery> deliveries(List<UserEvent> events) {
        List<UserEvent.Delivery> result = new ArrayList<>(events.size());
        boolean interrupted = false;
        for (UserEvent event : events) {
            switch (event) {
                case UserEvent.Message message ->
                    result.add(interrupted ? UserEvent.Delivery.QUEUE : message.delivery());
                case UserEvent.Interrupt _ -> {
                    interrupted = true;
                    result.add(null);
                }
                default -> result.add(null);
            }
        }
        return result;
    }

    /**
     * 回应事件的动作 ID。
     *
     * @param event 用户事件
     * @return 动作 ID；不是回应事件时为空
     */
    public static String actionIdOf(UserEvent event) {
        return switch (event) {
            case UserEvent.ToolConfirmation confirmation -> confirmation.actionId();
            case UserEvent.CustomToolResult result -> result.actionId();
            case UserEvent.RecoveryConfirmation recovery -> recovery.actionId();
            case UserEvent.Message _, UserEvent.Interrupt _ -> null;
        };
    }

    private static boolean accepts(UserEvent event, PendingAction.Type type) {
        return switch (event) {
            case UserEvent.ToolConfirmation _ ->
                type == PendingAction.Type.TOOL_CONFIRMATION || type == PendingAction.Type.PLAN_EXIT;
            case UserEvent.CustomToolResult _ -> type == PendingAction.Type.CUSTOM_TOOL_RESULT;
            case UserEvent.RecoveryConfirmation _ -> type == PendingAction.Type.RECOVERY_CONFIRMATION;
            case UserEvent.Message _, UserEvent.Interrupt _ -> false;
        };
    }

    private static String statusText(StoredPendingAction action) {
        return switch (action.status()) {
            case RESOLVED -> "回应";
            case EXPIRED -> "超时或作废";
            case PENDING -> "在等待";
        };
    }
}
