package cn.refinex.meridian.runtime.dispatch.domain;

import cn.refinex.meridian.engine.spi.engine.input.TurnInput;
import java.util.Objects;

/**
 * 入队一个轮次的参数。轮次类型由输入决定（{@link TurnInput#kind()}）。
 *
 * @param tenantId 租户 ID；必填
 * @param projectId 项目 ID；必填
 * @param sessionId 会话 ID；必填
 * @param threadId 线程 ID；必填
 * @param input 本轮的完整输入，写入 {@code turn_inputs}，恢复时用于重放；必填
 * @param priority 优先级，数值小的先被认领；普通轮次为 0
 * @param interruptRequested 入队时就带着中断请求（写入 {@code interrupt_requested_at}）：等待回应时被中断而生成的恢复轮，执行时
 *     把回应的结论写进上下文后在第一次推理前停止（架构设计「消息投递」）
 */
public record NewTurn(
        String tenantId,
        String projectId,
        String sessionId,
        String threadId,
        TurnInput input,
        int priority,
        boolean interruptRequested) {

    /**
     * 创建参数。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public NewTurn {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(input, "input");
    }

    /**
     * 创建不带中断请求的参数。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param threadId 线程 ID
     * @param input 输入
     * @param priority 优先级
     */
    public NewTurn(
            String tenantId, String projectId, String sessionId, String threadId, TurnInput input, int priority) {
        this(tenantId, projectId, sessionId, threadId, input, priority, false);
    }

    /**
     * 普通优先级的轮次。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param threadId 线程 ID
     * @param input 输入
     * @return 参数
     */
    public static NewTurn of(String tenantId, String projectId, String sessionId, String threadId, TurnInput input) {
        return new NewTurn(tenantId, projectId, sessionId, threadId, input, 0, false);
    }

    /**
     * 返回带着中断请求入队的副本。
     *
     * @return 新参数
     */
    public NewTurn withInterruptRequested() {
        return new NewTurn(tenantId, projectId, sessionId, threadId, input, priority, true);
    }
}
