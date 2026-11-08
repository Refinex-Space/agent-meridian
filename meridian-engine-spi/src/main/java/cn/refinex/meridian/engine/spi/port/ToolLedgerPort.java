package cn.refinex.meridian.engine.spi.port;

import cn.refinex.meridian.engine.spi.event.BlobRef;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * 工具调用账本，记录每次工具执行的开始与结束，崩溃恢复据此生成恢复说明，由 runtime 的 {@code tooling} 实现，
 * 表为 {@code rt.tool_invocations}，见架构设计「端口」「崩溃恢复」。
 *
 * <p>写入带租约条件，会话、租户与 epoch 从 Reactor Context 中的 {@code PlatformContext} 取。
 */
public interface ToolLedgerPort {

    /**
     * 记录一次工具执行开始，状态为 {@link Status#RUNNING}。必须在发起副作用之前完成。
     *
     * @param start 执行信息
     * @return 账本记录 ID（{@code tiv_…}）
     */
    Mono<String> recordStart(InvocationStart start);

    /**
     * 记录一次工具执行结束。
     *
     * @param invocationId 账本记录 ID
     * @param status 结束状态，不能是 {@link Status#RUNNING}
     * @param resultRef 结果在对象存储中的引用；可为空，表示结果不需要单独保存
     * @return 写入完成后结束
     */
    Mono<Void> recordFinish(String invocationId, Status status, BlobRef resultRef);

    /**
     * 记录一条在会话沙箱中执行的命令，写入审计（架构设计「智能体安全」：沙箱命令都写审计日志）。命令已经执行，所以这条写入不受租约
     * 约束；会话、轮次从 {@code PlatformContext} 取。
     *
     * @param command 命令与执行结果
     * @return 写入完成后结束
     */
    Mono<Void> recordSandboxCommand(SandboxCommand command);

    /**
     * 一条沙箱命令的执行记录。
     *
     * @param threadId 执行命令的线程；必填
     * @param toolUseId 发起命令的工具调用 ID；必填
     * @param command 智能体给出的命令原文；必填
     * @param exitCode 退出码；超时为 124
     * @param timedOut 是否超时被终止
     * @param duration 执行耗时；必填
     */
    record SandboxCommand(
            String threadId, String toolUseId, String command, int exitCode, boolean timedOut, Duration duration) {

        /**
         * 创建执行记录。
         *
         * @throws NullPointerException 缺少必填字段时
         */
        public SandboxCommand {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(toolUseId, "toolUseId");
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(duration, "duration");
        }
    }

    /**
     * 一次工具执行的开始信息。
     *
     * @param threadId 执行工具的线程（子智能体为子线程）；必填
     * @param toolUseId 工具调用 ID；必填
     * @param toolName 工具名；必填
     * @param sideEffect 副作用等级；必填
     * @param idempotencyKey 幂等键；{@code non_idempotent} 工具必填，其余可为空
     * @param input 入参；必填，可为空映射
     */
    record InvocationStart(
            String threadId,
            String toolUseId,
            String toolName,
            SideEffect sideEffect,
            String idempotencyKey,
            Map<String, Object> input) {

        /**
         * 创建开始信息。
         *
         * @throws NullPointerException 缺少必填字段时
         * @throws IllegalArgumentException {@code non_idempotent} 工具缺少幂等键时
         */
        public InvocationStart {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(toolUseId, "toolUseId");
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(sideEffect, "sideEffect");
            Objects.requireNonNull(input, "input");
            if (sideEffect == SideEffect.NON_IDEMPOTENT && idempotencyKey == null) {
                throw new IllegalArgumentException("non_idempotent 工具必须带幂等键：" + toolName);
            }
            input = Collections.unmodifiableMap(new LinkedHashMap<>(input));
        }
    }

    /** 工具执行的状态。 */
    enum Status {
        /** 执行中；进程被强杀后仍处于此状态的调用，恢复时视为状态未知。 */
        RUNNING,
        /** 成功。 */
        SUCCEEDED,
        /** 失败。 */
        FAILED,
        /** 被权限拒绝。 */
        DENIED,
        /** 被中断。 */
        INTERRUPTED
    }
}
