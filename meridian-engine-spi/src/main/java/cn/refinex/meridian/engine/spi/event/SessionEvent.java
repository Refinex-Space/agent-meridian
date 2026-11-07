package cn.refinex.meridian.engine.spi.event;

import java.util.List;
import java.util.Objects;

/**
 * session 域的事件：会话与线程的状态迁移、错误、用量与消息消费，见架构设计「事件」「状态机」。
 *
 * <p>这里的"session 域"指事件类型的前缀 {@code session.}，不是泛指会话里的所有事件。
 */
public sealed interface SessionEvent extends EngineEvent {

    /** {@code session.status_running}：轮次开始，由 Worker 在调用内核之前发出。 */
    record StatusRunning() implements SessionEvent {}

    /**
     * {@code session.status_idle}：轮次结束，线程回到空闲。
     *
     * @param stopReason 停止原因；必填
     */
    record StatusIdle(StopReason stopReason) implements SessionEvent {

        /**
         * 创建空闲事件。
         *
         * @throws NullPointerException 缺少停止原因时
         */
        public StatusIdle {
            Objects.requireNonNull(stopReason, "stopReason");
        }
    }

    /**
     * {@code session.status_requires_action}：产生了待处理动作，等待调用方回应。
     *
     * @param actions 本轮的全部待处理动作，至少一个
     */
    record StatusRequiresAction(List<PendingAction> actions) implements SessionEvent {

        /**
         * 创建等待回应事件。
         *
         * @throws IllegalArgumentException 没有待处理动作时
         */
        public StatusRequiresAction {
            actions = actions == null ? List.of() : List.copyOf(actions);
            if (actions.isEmpty()) {
                throw new IllegalArgumentException("session.status_requires_action 至少要有一个待处理动作");
            }
        }
    }

    /**
     * {@code session.status_rescheduled}：轮次被置回队列，等待其他 Worker 认领。
     *
     * @param reason 置回原因；必填
     */
    record StatusRescheduled(RescheduleReason reason) implements SessionEvent {

        /**
         * 创建重新排队事件。
         *
         * @throws NullPointerException 缺少原因时
         */
        public StatusRescheduled {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * {@code session.status_terminated}：会话被归档或删除，不再接受新输入。
     *
     * @param reason 终止原因；必填
     */
    record StatusTerminated(TerminationReason reason) implements SessionEvent {

        /**
         * 创建终止事件。
         *
         * @throws NullPointerException 缺少原因时
         */
        public StatusTerminated {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * {@code session.error}：本轮出错，随后一定有一个终态事件。
     *
     * @param code 错误码，格式 {@code <领域>.<原因>}，例如 {@code model.rate_limit_timeout}；必填
     * @param message 给调用方看的说明，不含凭据、提示词与内部信息；必填
     * @param retryStatus 重试状态；必填
     */
    record Error(String code, String message, RetryStatus retryStatus) implements SessionEvent {

        /**
         * 创建错误事件。
         *
         * @throws NullPointerException 缺少必填字段时
         */
        public Error {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(retryStatus, "retryStatus");
        }
    }

    /**
     * {@code session.usage}：一次模型调用的用量，计入会话（子智能体的用量计入父会话）。
     *
     * @param modelId 模型 ID（{@code mdl_…}）；必填
     * @param usage token 用量；必填
     */
    record Usage(String modelId, TokenUsage usage) implements SessionEvent {

        /**
         * 创建用量事件。
         *
         * @throws NullPointerException 缺少必填字段时
         */
        public Usage {
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(usage, "usage");
        }
    }

    /**
     * {@code session.message_consumed}：用户消息被某一轮消费（合并进 queue 轮或经 steer 注入）。
     *
     * @param eventIds 被消费的 {@code user.message} 事件 ID，至少一个
     */
    record MessageConsumed(List<String> eventIds) implements SessionEvent {

        /**
         * 创建消息消费事件。
         *
         * @throws IllegalArgumentException 没有事件 ID 时
         */
        public MessageConsumed {
            eventIds = eventIds == null ? List.of() : List.copyOf(eventIds);
            if (eventIds.isEmpty()) {
                throw new IllegalArgumentException("session.message_consumed 至少要有一个事件 ID");
            }
        }
    }

    /**
     * {@code session.thread_created}：{@code spawn_subagent} 创建了一条子线程。
     *
     * @param threadId 新线程 ID（{@code thr_…}）；必填
     * @param parentThreadId 父线程 ID；必填
     * @param parentToolUseId 拉起它的 {@code spawn_subagent} 调用 ID；必填
     * @param agentName 子智能体名；必填
     */
    record ThreadCreated(String threadId, String parentThreadId, String parentToolUseId, String agentName)
            implements SessionEvent {

        /**
         * 创建子线程事件。
         *
         * @throws NullPointerException 缺少必填字段时
         */
        public ThreadCreated {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(parentThreadId, "parentThreadId");
            Objects.requireNonNull(parentToolUseId, "parentToolUseId");
            Objects.requireNonNull(agentName, "agentName");
        }
    }

    /**
     * {@code session.thread_status_running}：子线程开始执行。
     *
     * @param threadId 线程 ID；必填
     */
    record ThreadStatusRunning(String threadId) implements SessionEvent {

        /**
         * 创建线程运行事件。
         *
         * @throws NullPointerException 缺少线程 ID 时
         */
        public ThreadStatusRunning {
            Objects.requireNonNull(threadId, "threadId");
        }
    }

    /**
     * {@code session.thread_status_idle}：子线程执行结束。
     *
     * @param threadId 线程 ID；必填
     * @param stopReason 停止原因；必填
     */
    record ThreadStatusIdle(String threadId, StopReason stopReason) implements SessionEvent {

        /**
         * 创建线程空闲事件。
         *
         * @throws NullPointerException 缺少必填字段时
         */
        public ThreadStatusIdle {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(stopReason, "stopReason");
        }
    }

    /** 轮次被置回队列的原因，见架构设计「一轮执行的生命周期」中"置回队列的三种情况"。 */
    enum RescheduleReason {
        /** 租约过期，由回收器置回，{@code recovery_attempt} 加一。 */
        LEASE_EXPIRED("lease_expired"),
        /** 节点优雅下线，{@code recovery_attempt} 不变。 */
        NODE_SHUTDOWN("node_shutdown"),
        /**
         * 可重试的错误（限流等待超时、可重试的供应商错误），{@code recovery_attempt} 加一；之前一定有
         * {@code session.error}（{@code retry_status=retryable}）（ADR-0026）。
         */
        RETRYABLE_ERROR("retryable_error");

        private final String value;

        RescheduleReason(String value) {
            this.value = value;
        }

        /**
         * 返回事件中使用的取值。
         *
         * @return 小写下划线形式的取值
         */
        public String value() {
            return value;
        }
    }

    /** 会话终止的原因。 */
    enum TerminationReason {
        /** 归档。 */
        ARCHIVED("archived"),
        /** 删除。 */
        DELETED("deleted");

        private final String value;

        TerminationReason(String value) {
            this.value = value;
        }

        /**
         * 返回事件中使用的取值。
         *
         * @return 小写取值
         */
        public String value() {
            return value;
        }
    }

    /** {@code session.error} 的重试状态。 */
    enum RetryStatus {
        /** 可重试：平台把轮次置回队列，{@code recovery_attempt} 加一（供应商 5xx、限流等待超时等）。 */
        RETRYABLE("retryable"),
        /** 可重试的错误，但恢复次数已用尽，本轮以 {@code stop_reason=error} 结束。 */
        EXHAUSTED("exhausted"),
        /** 不可重试，本轮以终态事件结束。 */
        TERMINAL("terminal");

        private final String value;

        RetryStatus(String value) {
            this.value = value;
        }

        /**
         * 返回事件中使用的取值。
         *
         * @return 小写取值
         */
        public String value() {
            return value;
        }
    }
}
