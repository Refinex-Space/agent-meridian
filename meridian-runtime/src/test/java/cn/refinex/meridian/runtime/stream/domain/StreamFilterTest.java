package cn.refinex.meridian.runtime.stream.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.engine.spi.event.EventType;
import org.junit.jupiter.api.Test;

/** {@link StreamFilter}：会话流、{@code ?threads=main} 与线程流的事件范围（架构设计「SSE 协议」）。 */
class StreamFilterTest {

    @Test
    void sessionStreamAcceptsEverything() {
        assertThat(new StreamFilter.All().accepts("thr_child", EventType.AGENT_MESSAGE))
                .isTrue();
        assertThat(new StreamFilter.All().accepts(null, EventType.SESSION_STATUS_TERMINATED))
                .isTrue();
    }

    @Test
    void mainFilterAcceptsMainSessionLevelAndThreadStatusEvents() {
        StreamFilter main = new StreamFilter.Main("thr_main");

        assertThat(main.accepts("thr_main", EventType.AGENT_MESSAGE)).isTrue();
        assertThat(main.accepts(null, EventType.SESSION_STATUS_TERMINATED)).isTrue();
        assertThat(main.accepts("thr_child", EventType.SESSION_THREAD_STATUS_IDLE))
                .isTrue();
        assertThat(main.accepts("thr_child", EventType.SESSION_THREAD_CREATED)).isTrue();
        assertThat(main.accepts("thr_child", EventType.AGENT_MESSAGE)).isFalse();
        assertThatThrownBy(() -> new StreamFilter.Main(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void threadFilterAcceptsOnlyItsOwnEvents() {
        StreamFilter thread = new StreamFilter.Thread("thr_child");

        assertThat(thread.accepts("thr_child", EventType.AGENT_TOOL_USE)).isTrue();
        assertThat(thread.accepts("thr_main", EventType.AGENT_TOOL_USE)).isFalse();
        assertThat(thread.accepts(null, EventType.SESSION_STATUS_TERMINATED)).isFalse();
        assertThatThrownBy(() -> new StreamFilter.Thread(null)).isInstanceOf(NullPointerException.class);
    }
}
