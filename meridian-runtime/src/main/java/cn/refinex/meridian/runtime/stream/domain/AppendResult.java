package cn.refinex.meridian.runtime.stream.domain;

import java.util.List;

/**
 * 一次事件写入的结果。租约条件不满足不是异常，而是明确的结果，调用方必须处理（编码规范「SQL」）。
 */
public sealed interface AppendResult {

    /**
     * 已写入。
     *
     * @param events 写入的事件，顺序与批次一致，seq 连续
     */
    record Appended(List<StoredEvent> events) implements AppendResult {

        /** 复制事件列表。 */
        public Appended {
            events = List.copyOf(events);
        }

        /**
         * 本批最后一个事件的 seq。
         *
         * @return seq
         */
        public long lastSeq() {
            return events.getLast().seq();
        }
    }

    /**
     * 租约条件不满足，什么都没写，也没有占用 seq。
     *
     * @param turnId 写入方声称持有租约的轮次
     * @param epoch 写入方的租约 epoch
     */
    record LeaseLost(String turnId, long epoch) implements AppendResult {}
}
