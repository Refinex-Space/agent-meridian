package cn.refinex.meridian.engine.spi.port;

import cn.refinex.meridian.engine.spi.event.StopReason;
import reactor.core.publisher.Mono;

/**
 * 创建子线程、更新线程状态，供 {@code spawn_subagent} 使用，由 runtime 的 {@code session} 实现，表为
 * {@code rt.threads}，见架构设计「端口」「子智能体」。
 *
 * <p>线程的记录与对应的会话事件在同一事务中写入（ADR-0040）：创建子线程同时写 {@code session.thread_created}，更新状态就是写
 * {@code session.thread_status_running} 或 {@code session.thread_status_idle}，线程行的状态由事件写入方在同一事务中推进，所以
 * {@code rt.threads.status} 不会与事件日志分叉。事件的 {@code thread_id} 是子线程。
 *
 * <p>会话、轮次与租约 epoch 从 Reactor Context 中的 {@code PlatformContext} 取，写入带父轮次的租约条件；租约已不是本次执行的
 * epoch 时以 {@code LeaseLostException} 结束，什么都不写。
 */
public interface ThreadRegistryPort {

    /**
     * 创建一条子线程并写 {@code session.thread_created}。线程 ID 由平台生成，所以并行的子智能体事件不会混淆。
     *
     * @param parentThreadId 父线程 ID
     * @param parentToolUseId 拉起它的 {@code spawn_subagent} 调用 ID，记入 {@code parent_tool_use_id}
     * @param agentName 子智能体名
     * @return 新线程 ID（{@code thr_…}）
     */
    Mono<String> createChildThread(String parentThreadId, String parentToolUseId, String agentName);

    /**
     * 更新子线程的状态：写 {@code session.thread_status_running} 或 {@code session.thread_status_idle}。
     *
     * @param threadId 子线程 ID
     * @param status 新状态
     * @param stopReason 停止原因：{@link ThreadStatus#IDLE} 时必填，{@link ThreadStatus#RUNNING} 时必须为空
     * @return 写入后完成
     * @throws IllegalArgumentException 停止原因与状态不相符时
     */
    Mono<Void> updateStatus(String threadId, ThreadStatus status, StopReason stopReason);

    /** 线程状态，对应 {@code session.thread_status_*} 事件。 */
    enum ThreadStatus {
        /** 正在执行。 */
        RUNNING,
        /** 执行结束，空闲。 */
        IDLE;

        /**
         * 校验停止原因与状态是否相符：空闲必须有停止原因，运行中不能有。
         *
         * @param stopReason 停止原因；可为空
         * @throws IllegalArgumentException 不相符时
         */
        public void check(StopReason stopReason) {
            if ((this == IDLE) != (stopReason != null)) {
                throw new IllegalArgumentException("线程状态 " + this + " 与停止原因 " + stopReason + " 不相符");
            }
        }
    }
}
