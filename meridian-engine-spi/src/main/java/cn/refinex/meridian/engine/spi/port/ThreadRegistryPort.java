package cn.refinex.meridian.engine.spi.port;

import reactor.core.publisher.Mono;

/**
 * 创建子线程、更新线程状态，供 {@code spawn_subagent} 使用，由 runtime 的 {@code session} 实现，表为
 * {@code rt.threads}，见架构设计「端口」「子智能体」。
 *
 * <p>会话从 Reactor Context 中的 {@code PlatformContext} 取，写入带父轮次的租约条件。
 */
public interface ThreadRegistryPort {

    /**
     * 创建一条子线程，ID 由平台生成，所以并行的子智能体事件不会混淆。
     *
     * @param parentThreadId 父线程 ID
     * @param parentToolUseId 拉起它的 {@code spawn_subagent} 调用 ID，记入 {@code parent_tool_use_id}
     * @param agentName 子智能体名
     * @return 新线程 ID（{@code thr_…}）
     */
    Mono<String> createChildThread(String parentThreadId, String parentToolUseId, String agentName);

    /**
     * 更新线程状态。
     *
     * @param threadId 线程 ID
     * @param status 新状态
     * @return 更新后完成
     */
    Mono<Void> updateStatus(String threadId, ThreadStatus status);

    /** 线程状态，对应 {@code session.thread_status_*} 事件。 */
    enum ThreadStatus {
        /** 正在执行。 */
        RUNNING,
        /** 执行结束，空闲。 */
        IDLE
    }
}
