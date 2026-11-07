package cn.refinex.meridian.runtime.dispatch.app;

/**
 * 发布调度唤醒通知 {@code dispatch.wakeup}（架构设计「一轮执行的生命周期」）。通知只用来降低延迟：丢失时 Worker 在下一次
 * 轮询（默认 1 秒）发现新轮次，所以发布失败不影响正确性。
 */
@FunctionalInterface
public interface WakeupPublisher {

    /** 发布一次唤醒通知。失败时只记录，不抛出。 */
    void publish();
}
