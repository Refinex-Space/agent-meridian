package cn.refinex.meridian.engine.spi.event;

import cn.refinex.meridian.engine.spi.engine.input.MessageContent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * user 域的事件：调用方经 {@code POST /v1/sessions/{id}/events} 发送，由 API 持久化，内核不产生，
 * 见架构设计「事件」「消息投递」「待处理动作」。
 */
public sealed interface UserEvent extends EngineEvent {

    /**
     * {@code user.message}：一条用户消息。
     *
     * @param content 内容块，至少一个
     * @param delivery 投递方式；必填
     */
    record Message(List<MessageContent> content, Delivery delivery) implements UserEvent {

        /**
         * 创建用户消息事件。
         *
         * @throws NullPointerException 缺少投递方式时
         * @throws IllegalArgumentException 没有内容块时
         */
        public Message {
            Objects.requireNonNull(delivery, "delivery");
            content = content == null ? List.of() : List.copyOf(content);
            if (content.isEmpty()) {
                throw new IllegalArgumentException("user.message 至少要有一个内容块");
            }
        }
    }

    /** {@code user.interrupt}：请求中断当前轮。 */
    record Interrupt() implements UserEvent {}

    /**
     * {@code user.tool_confirmation}：回应 {@code tool_confirmation} 或 {@code plan_exit} 动作。
     *
     * @param actionId 被回应的动作 ID（{@code act_…}）；必填
     * @param approved 是否批准
     * @param updatedInput 审批人修改后的入参；可为空，表示按原入参执行
     * @param denyReason 拒绝理由；可为空
     * @param rememberScope "记住此决定"的作用域；必填，不记住时为 {@link RememberScope#NONE}
     * @param rememberRule 记住哪条建议规则（动作 {@code suggested_rules} 中的一条）；为空表示按原来的方式记（D21，ADR-0053）
     */
    record ToolConfirmation(
            String actionId,
            boolean approved,
            Map<String, Object> updatedInput,
            String denyReason,
            RememberScope rememberScope,
            String rememberRule)
            implements UserEvent {

        /**
         * 创建工具确认事件。
         *
         * @throws NullPointerException 缺少动作 ID 或记住作用域时
         */
        public ToolConfirmation {
            Objects.requireNonNull(actionId, "actionId");
            Objects.requireNonNull(rememberScope, "rememberScope");
            updatedInput = updatedInput == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(updatedInput));
        }

        /**
         * 创建不指定建议规则的工具确认事件。
         *
         * @param actionId 被回应的动作 ID
         * @param approved 是否批准
         * @param updatedInput 修改后的入参；可为空
         * @param denyReason 拒绝理由；可为空
         * @param rememberScope "记住此决定"的作用域
         */
        public ToolConfirmation(
                String actionId,
                boolean approved,
                Map<String, Object> updatedInput,
                String denyReason,
                RememberScope rememberScope) {
            this(actionId, approved, updatedInput, denyReason, rememberScope, null);
        }
    }

    /**
     * {@code user.custom_tool_result}：回传自定义工具的执行结果。
     *
     * @param actionId 被回应的动作 ID（{@code act_…}）；必填
     * @param content 结果内容块；可为空列表
     * @param error 是否为错误结果
     */
    record CustomToolResult(String actionId, List<MessageContent> content, boolean error) implements UserEvent {

        /**
         * 创建自定义工具结果事件。
         *
         * @throws NullPointerException 缺少动作 ID 时
         */
        public CustomToolResult {
            Objects.requireNonNull(actionId, "actionId");
            content = content == null ? List.of() : List.copyOf(content);
        }
    }

    /**
     * {@code user.recovery_confirmation}：决定是否重试一个状态未知的 {@code non_idempotent} 调用。
     *
     * @param actionId 被回应的动作 ID（{@code act_…}）；必填
     * @param decision 决定；必填
     */
    record RecoveryConfirmation(String actionId, RecoveryDecision decision) implements UserEvent {

        /**
         * 创建恢复确认事件。
         *
         * @throws NullPointerException 缺少必填字段时
         */
        public RecoveryConfirmation {
            Objects.requireNonNull(actionId, "actionId");
            Objects.requireNonNull(decision, "decision");
        }
    }

    /** 用户消息的投递方式，见架构设计「消息投递」。 */
    enum Delivery {
        /** 默认：线程运行中时排队，本轮结束后合并成下一轮。 */
        QUEUE("queue"),
        /** 线程运行中时在下一次推理前注入当前轮；来不及注入时降级为 queue。 */
        STEER("steer");

        private final String value;

        Delivery(String value) {
            this.value = value;
        }

        /**
         * 返回 API 中使用的取值。
         *
         * @return 小写取值
         */
        public String value() {
            return value;
        }
    }

    /** "记住此决定"的作用域，写入 {@code pending_actions.remember_scope}。 */
    enum RememberScope {
        /** 不记住。 */
        NONE("none"),
        /** 记为会话级规则。 */
        SESSION("session"),
        /** 记为项目级规则。 */
        PROJECT("project");

        private final String value;

        RememberScope(String value) {
            this.value = value;
        }

        /**
         * 返回 API 中使用的取值。
         *
         * @return 小写取值
         */
        public String value() {
            return value;
        }
    }

    /** 恢复确认的决定。 */
    enum RecoveryDecision {
        /** 用原幂等键重新执行该调用。 */
        RETRY("retry"),
        /** 跳过，只在恢复说明里注明。 */
        SKIP("skip");

        private final String value;

        RecoveryDecision(String value) {
            this.value = value;
        }

        /**
         * 返回 API 中使用的取值。
         *
         * @return 小写取值
         */
        public String value() {
            return value;
        }
    }
}
