package cn.refinex.meridian.engine.agentscope.turn;

import cn.refinex.meridian.engine.spi.event.StopReason;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 一次 {@code spawn_subagent} 的结果，由 {@code SpawnSubagentTool} 转成交给父智能体的工具结果（架构设计「子智能体」）。
 */
public sealed interface SubagentOutcome {

    /**
     * 子智能体执行结束：按 {@code GenerateReason} 与异常映射出停止原因（与主线程的一轮相同，见「状态机」）。
     *
     * @param threadId 子线程 ID；必填
     * @param stopReason 停止原因；必填
     * @param text 子智能体的最终消息；可为空字符串
     * @param error 出错时给模型看的说明（{@code session.error} 的 message）；没有出错时为空
     */
    record Finished(String threadId, StopReason stopReason, String text, String error) implements SubagentOutcome {

        /**
         * 创建结果。
         *
         * @throws NullPointerException 缺少必填字段时
         */
        public Finished {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(stopReason, "stopReason");
            text = text == null ? "" : text;
        }
    }

    /**
     * 子智能体以 {@code PERMISSION_ASKING} 或 {@code TOOL_SUSPENDED} 结束：子线程没有人能审批，也不能挂起等待业务方，这些调用一律
     * 视为被拒绝，子线程以 {@code tools_denied} 结束。
     *
     * @param threadId 子线程 ID；必填
     * @param toolNames 等待确认或挂起的工具名，按出现顺序；可为空列表
     */
    record Rejected(String threadId, List<String> toolNames) implements SubagentOutcome {

        /**
         * 创建结果。
         *
         * @throws NullPointerException 缺少子线程 ID 时
         */
        public Rejected {
            Objects.requireNonNull(threadId, "threadId");
            toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
        }
    }

    /**
     * 超时：子智能体的订阅已被取消，子线程以 {@code interrupted} 结束（ADR-0040）。
     *
     * @param threadId 子线程 ID；必填
     * @param timeout 子智能体配置的超时；必填
     */
    record TimedOut(String threadId, Duration timeout) implements SubagentOutcome {

        /**
         * 创建结果。
         *
         * @throws NullPointerException 缺少必填字段时
         */
        public TimedOut {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(timeout, "timeout");
        }
    }

    /**
     * 没有拉起：本轮已达拉起上限，或父线程已被中断。不创建子线程。
     *
     * @param reason 原因；必填
     */
    record Refused(RefusalReason reason) implements SubagentOutcome {

        /**
         * 创建结果。
         *
         * @throws NullPointerException 缺少原因时
         */
        public Refused {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** 没有拉起子智能体的原因。 */
    enum RefusalReason {
        /** 本轮的拉起数量已达上限（{@code PolicyMiddleware}）。 */
        LIMIT_REACHED,
        /** 父线程在本次调用开始前已被中断。 */
        INTERRUPTED
    }
}
