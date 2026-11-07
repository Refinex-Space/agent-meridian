package cn.refinex.meridian.engine.spi.engine;

import java.util.Objects;

/**
 * 对一条线程的引用，见架构设计「核心概念」：主智能体是主线程，每次拉起子智能体产生一条子线程。
 *
 * <p>线程 ID 也是 AgentScope 的 {@code RuntimeContext.sessionId}，即状态键的一部分（架构设计「身份映射」）。
 *
 * @param sessionId 会话 ID（{@code ses_…}）；必填
 * @param threadId 线程 ID（{@code thr_…}）；必填
 */
public record ThreadRef(String sessionId, String threadId) {

    /**
     * 创建线程引用。
     *
     * @throws NullPointerException 缺少会话 ID 或线程 ID 时
     */
    public ThreadRef {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(threadId, "threadId");
    }
}
