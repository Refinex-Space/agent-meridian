package cn.refinex.meridian.runtime.dispatch.app;

/** 入队的结果。线程已有未结束的轮次不是异常，而是明确的结果，调用方据此把消息留在收件箱（架构设计「消息投递」）。 */
public sealed interface EnqueueResult {

    /**
     * 已入队。
     *
     * @param turnId 新轮次的 ID
     */
    record Enqueued(String turnId) implements EnqueueResult {}

    /**
     * 线程已有 {@code queued}、{@code running} 或 {@code awaiting_action} 的轮次，没有插入任何数据。
     *
     * @param threadId 线程 ID
     */
    record ThreadBusy(String threadId) implements EnqueueResult {}
}
