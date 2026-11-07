package cn.refinex.meridian.engine.spi.engine.input;

import java.util.List;
import java.util.Objects;

/**
 * 一轮的输入，只能是四种之一，与轮次类型 {@code kind}（{@code messages}、{@code confirmations}、
 * {@code custom_tool_results}、{@code recovery}）一一对应，见架构设计「内核适配层」「状态机」。
 *
 * <p>前三种输入的 {@code recovery} 可为空：为空表示正常轮次；不为空表示崩溃恢复时重放的输入，附带恢复说明。
 * {@link RecoveryOnly} 不重放任何输入，用于恢复保存了中途状态的执行，或回应恢复确认。
 */
public sealed interface TurnInput {

    /**
     * 本轮附带的恢复说明。
     *
     * @return 恢复说明；为空表示不是恢复轮
     */
    RecoveryNote recovery();

    /**
     * 返回与本输入对应的轮次类型，即 {@code rt.turns.kind} 的取值。
     *
     * @return 轮次类型
     */
    default String kind() {
        return switch (this) {
            case NewMessages _ -> "messages";
            case Confirmations _ -> "confirmations";
            case CustomToolResults _ -> "custom_tool_results";
            case RecoveryOnly _ -> "recovery";
        };
    }

    /**
     * 新的用户消息（一条或多条合并的 queue 消息）。
     *
     * @param messages 用户消息，至少一条
     * @param recovery 恢复说明；可为空
     */
    record NewMessages(List<InboundMessage> messages, RecoveryNote recovery) implements TurnInput {

        /**
         * 创建消息输入。
         *
         * @throws IllegalArgumentException 没有消息时
         */
        public NewMessages {
            messages = messages == null ? List.of() : List.copyOf(messages);
            if (messages.isEmpty()) {
                throw new IllegalArgumentException("messages 轮至少要有一条消息");
            }
        }
    }

    /**
     * 对待确认工具调用的回应，一轮中所有待确认调用都有了回应（含超时）才生成。
     *
     * @param confirmations 工具确认，至少一条
     * @param recovery 恢复说明；可为空
     */
    record Confirmations(List<ToolConfirmation> confirmations, RecoveryNote recovery) implements TurnInput {

        /**
         * 创建确认输入。
         *
         * @throws IllegalArgumentException 没有确认时
         */
        public Confirmations {
            confirmations = confirmations == null ? List.of() : List.copyOf(confirmations);
            if (confirmations.isEmpty()) {
                throw new IllegalArgumentException("confirmations 轮至少要有一条确认");
            }
        }
    }

    /**
     * 业务方回传的自定义工具结果。
     *
     * @param results 自定义工具结果，至少一条
     * @param recovery 恢复说明；可为空
     */
    record CustomToolResults(List<CustomToolResult> results, RecoveryNote recovery) implements TurnInput {

        /**
         * 创建自定义工具结果输入。
         *
         * @throws IllegalArgumentException 没有结果时
         */
        public CustomToolResults {
            results = results == null ? List.of() : List.copyOf(results);
            if (results.isEmpty()) {
                throw new IllegalArgumentException("custom_tool_results 轮至少要有一条结果");
            }
        }
    }

    /**
     * 只有恢复说明、不重放任何输入的恢复轮。
     *
     * @param recovery 恢复说明；必填
     */
    record RecoveryOnly(RecoveryNote recovery) implements TurnInput {

        /**
         * 创建恢复输入。
         *
         * @throws NullPointerException 缺少恢复说明时
         */
        public RecoveryOnly {
            Objects.requireNonNull(recovery, "recovery");
        }
    }
}
