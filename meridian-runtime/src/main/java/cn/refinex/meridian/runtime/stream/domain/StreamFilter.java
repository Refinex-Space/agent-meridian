package cn.refinex.meridian.runtime.stream.domain;

import cn.refinex.meridian.engine.spi.event.EventType;
import java.util.Objects;
import java.util.Set;

/**
 * SSE 连接订阅的事件范围（架构设计「SSE 协议」中会话流与线程流的 seq 语义）。
 *
 * <ul>
 *   <li>{@link All}：会话流，包含全部线程，seq 连续。
 *   <li>{@link Main}：会话流加 {@code ?threads=main}，只含主线程、会话级事件与子线程的状态事件，seq 不连续。
 *   <li>{@link Thread}：线程流，只含本线程的事件，seq 不连续。
 * </ul>
 * seq 不连续时，其他线程占用的序号不算缺失：服务端按会话的全部序号判断是否跳号，只补发范围内的事件。
 */
public sealed interface StreamFilter {

    /** 子线程的状态事件，{@code ?threads=main} 也推送。 */
    Set<EventType> THREAD_STATUS_EVENTS = Set.of(
            EventType.SESSION_THREAD_CREATED,
            EventType.SESSION_THREAD_STATUS_RUNNING,
            EventType.SESSION_THREAD_STATUS_IDLE);

    /**
     * 事件是否在订阅范围内。
     *
     * @param threadId 事件所属线程；可为空，表示会话级事件
     * @param type 事件类型
     * @return 在范围内时为 true
     */
    boolean accepts(String threadId, EventType type);

    /** 会话流：全部事件。 */
    record All() implements StreamFilter {
        @Override
        public boolean accepts(String threadId, EventType type) {
            return true;
        }
    }

    /**
     * {@code ?threads=main}：主线程、会话级事件与子线程的状态事件。
     *
     * @param mainThreadId 主线程 ID；必填
     */
    record Main(String mainThreadId) implements StreamFilter {

        /**
         * 创建过滤条件。
         *
         * @throws NullPointerException 缺少主线程 ID 时
         */
        public Main {
            Objects.requireNonNull(mainThreadId, "mainThreadId");
        }

        @Override
        public boolean accepts(String threadId, EventType type) {
            return threadId == null || threadId.equals(mainThreadId) || THREAD_STATUS_EVENTS.contains(type);
        }
    }

    /**
     * 线程流：只有本线程的事件。
     *
     * @param threadId 线程 ID；必填
     */
    record Thread(String threadId) implements StreamFilter {

        /**
         * 创建过滤条件。
         *
         * @throws NullPointerException 缺少线程 ID 时
         */
        public Thread {
            Objects.requireNonNull(threadId, "threadId");
        }

        @Override
        public boolean accepts(String eventThreadId, EventType type) {
            return threadId.equals(eventThreadId);
        }
    }
}
