package cn.refinex.meridian.runtime.stream.domain;

import cn.refinex.meridian.engine.spi.event.EngineEvent;
import cn.refinex.meridian.engine.spi.event.SessionEvent;
import cn.refinex.meridian.runtime.session.domain.SessionStatus;
import java.util.Optional;

/**
 * 状态事件对会话与线程状态的投影。事件写入方在插入事件的同一事务中执行它，所以 {@code rt.sessions.status}、
 * {@code rt.threads.status} 与事件日志不会分叉（架构设计「状态机」）。
 *
 * <ul>
 *   <li>{@code session.status_*}：会话进入对应状态；事件带线程时，该线程（主线程）进入同一状态，终止事件除外。
 *   <li>{@code session.thread_status_running}、{@code session.thread_status_idle}：事件内容中的子线程进入对应状态。
 * </ul>
 *
 * @param sessionStatus 会话的新状态；可为空，表示会话状态不变
 * @param threadId 状态变化的线程；可为空，表示没有线程状态变化
 * @param threadStatus 线程的新状态；{@code threadId} 为空时为空
 */
public record StatusChange(SessionStatus sessionStatus, String threadId, SessionStatus threadStatus) {

    /**
     * 计算一个事件引起的状态变化。
     *
     * @param eventThreadId 事件所属线程；可为空
     * @param event 事件
     * @return 状态变化；不是状态事件时为空
     */
    public static Optional<StatusChange> of(String eventThreadId, EngineEvent event) {
        return switch (event) {
            case SessionEvent.StatusRunning _ -> Optional.of(sessionAndThread(SessionStatus.RUNNING, eventThreadId));
            case SessionEvent.StatusIdle _ -> Optional.of(sessionAndThread(SessionStatus.IDLE, eventThreadId));
            case SessionEvent.StatusRequiresAction _ ->
                Optional.of(sessionAndThread(SessionStatus.REQUIRES_ACTION, eventThreadId));
            case SessionEvent.StatusRescheduled _ ->
                Optional.of(sessionAndThread(SessionStatus.RESCHEDULED, eventThreadId));
            case SessionEvent.StatusTerminated _ -> Optional.of(new StatusChange(SessionStatus.TERMINATED, null, null));
            case SessionEvent.ThreadStatusRunning running ->
                Optional.of(new StatusChange(null, running.threadId(), SessionStatus.RUNNING));
            case SessionEvent.ThreadStatusIdle idle ->
                Optional.of(new StatusChange(null, idle.threadId(), SessionStatus.IDLE));
            default -> Optional.empty();
        };
    }

    private static StatusChange sessionAndThread(SessionStatus status, String threadId) {
        return new StatusChange(status, threadId, threadId == null ? null : status);
    }
}
