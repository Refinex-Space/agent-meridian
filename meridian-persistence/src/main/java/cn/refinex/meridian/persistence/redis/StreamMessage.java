package cn.refinex.meridian.persistence.redis;

import java.util.Map;

/**
 * 从 Redis Stream 读到的一条消息。
 *
 * @param stream Stream 的键
 * @param recordId Redis 分配的条目 ID（形如 {@code 1730959789123-0}），确认（XACK）与认领（XCLAIM）时使用；
 *     同一业务消息重投时条目 ID 可能不同，消费方幂等应依据载荷中的业务 ID
 * @param fields 条目的字段
 */
public record StreamMessage(String stream, String recordId, Map<String, String> fields) {

    /** 复制字段，保证不可变。 */
    public StreamMessage {
        fields = Map.copyOf(fields);
    }

    /**
     * 读取一个字段。
     *
     * @param name 字段名
     * @return 字段值，不存在时为空
     */
    public String field(String name) {
        return fields.get(name);
    }
}
