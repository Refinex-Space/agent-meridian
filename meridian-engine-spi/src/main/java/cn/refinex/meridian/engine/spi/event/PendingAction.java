package cn.refinex.meridian.engine.spi.event;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一个需要调用方回应的待处理动作，即 {@code session.status_requires_action} 事件 {@code data.actions} 的元素，
 * 见架构设计「待处理动作」「事件」。
 *
 * <p>前三种动作由内核产生，{@code recovery_confirmation} 由平台在崩溃恢复时产生；每种动作都关联一个工具调用。
 * 内核产生动作时生成动作 ID、不设截止时间，runtime 写入 {@code pending_actions} 时按超时配置（默认 30 分钟）
 * 用 {@link #withExpiresAt} 补上。
 *
 * @param id 动作 ID（{@code act_…}）；必填
 * @param type 动作类型；必填
 * @param toolUseId 相关工具调用 ID；必填
 * @param toolName 相关工具名；必填
 * @param input 相关工具调用的入参；必填，可为空映射
 * @param expiresAt 截止时间，超时按动作类型处理；可为空，表示尚未由 runtime 设置
 * @param suggestedRules {@code tool_confirmation} 的建议规则：工具按这次的入参给出的"记住此决定"规则内容（命令前缀、路径等），回应时
 *     可以选一条记住；其他类型为空列表（登记事项 D21，ADR-0053）
 */
public record PendingAction(
        String id,
        Type type,
        String toolUseId,
        String toolName,
        Map<String, Object> input,
        Instant expiresAt,
        List<String> suggestedRules) {

    /**
     * 创建待处理动作。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public PendingAction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(toolUseId, "toolUseId");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(input, "input");
        input = Collections.unmodifiableMap(new LinkedHashMap<>(input));
        suggestedRules = suggestedRules == null ? List.of() : List.copyOf(suggestedRules);
    }

    /**
     * 创建没有建议规则的待处理动作。
     *
     * @param id 动作 ID
     * @param type 动作类型
     * @param toolUseId 相关工具调用 ID
     * @param toolName 相关工具名
     * @param input 相关工具调用的入参
     * @param expiresAt 截止时间；可为空
     */
    public PendingAction(
            String id, Type type, String toolUseId, String toolName, Map<String, Object> input, Instant expiresAt) {
        this(id, type, toolUseId, toolName, input, expiresAt, List.of());
    }

    /**
     * 返回设置了截止时间的副本。
     *
     * @param deadline 截止时间
     * @return 新的动作
     */
    public PendingAction withExpiresAt(Instant deadline) {
        return new PendingAction(id, type, toolUseId, toolName, input, deadline, suggestedRules);
    }

    /** 待处理动作的类型，见架构设计「待处理动作」表。 */
    public enum Type {
        /** 权限判为 ask 的工具调用，回应事件 {@code user.tool_confirmation}，超时视为拒绝。 */
        TOOL_CONFIRMATION("tool_confirmation"),
        /** {@code plan_exit} 请求退出计划模式，回应事件 {@code user.tool_confirmation}，超时视为拒绝。 */
        PLAN_EXIT("plan_exit"),
        /** 智能体调用了自定义工具，回应事件 {@code user.custom_tool_result}，超时以 {@code timeout} 错误结果回传。 */
        CUSTOM_TOOL_RESULT("custom_tool_result"),
        /** 崩溃恢复时状态未知的 {@code non_idempotent} 调用，回应事件 {@code user.recovery_confirmation}，超时视为 skip。 */
        RECOVERY_CONFIRMATION("recovery_confirmation");

        private final String value;

        Type(String value) {
            this.value = value;
        }

        /**
         * 返回事件与 API 中使用的取值。
         *
         * @return 小写下划线形式的取值
         */
        public String value() {
            return value;
        }
    }
}
