package cn.refinex.meridian.controlplane.support;

import static org.assertj.core.api.Assertions.fail;

import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.persistence.redis.RedisMessaging;
import cn.refinex.meridian.persistence.redis.RedisStreamOutboxPublisher;
import cn.refinex.meridian.testsupport.TestDatabase;
import cn.refinex.meridian.testsupport.outbox.OutboxSamples;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.node.ObjectNode;

/**
 * 用量与审计消费者的测试辅助（T16）：按 outbox 的条目格式把消息发布到 Redis Stream（就像 runtime 的 Relay），等待消费者入库，
 * 以迁移角色读库核对。
 */
public final class LedgerStreams {

    /** 等待消费者入库的上限。 */
    private static final Duration WAIT = Duration.ofSeconds(20);

    private LedgerStreams() {}

    /**
     * 以契约样本为底稿，替换部分字段（值为 null 时写成 JSON null）。
     *
     * @param topic 主题
     * @param sample 样本名
     * @param overrides 要替换的字段
     * @return 载荷 JSON
     */
    public static String sample(String topic, String sample, Map<String, Object> overrides) {
        ObjectNode node = (ObjectNode) JsonMappers.lenient().readTree(OutboxSamples.read(topic, sample));
        overrides.forEach(
                (field, value) -> node.set(field, JsonMappers.lenient().valueToTree(value)));
        return node.toString();
    }

    /**
     * 字段替换表（允许 null 值）。
     *
     * @param pairs 字段与值交替出现
     * @return 替换表
     */
    public static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /**
     * 以 outbox 条目的格式发布一条消息（新的消息 ID）。
     *
     * @param redis Redis 客户端
     * @param topic 主题，即 Stream 键
     * @param payload 载荷
     * @return outbox 消息 ID
     */
    public static String publish(RedisMessaging redis, String topic, String payload) {
        String id = Ids.newUlid();
        publish(redis, topic, id, payload);
        return id;
    }

    /**
     * 以指定的消息 ID 发布（模拟重投）。
     *
     * @param redis Redis 客户端
     * @param topic 主题
     * @param id outbox 消息 ID
     * @param payload 载荷
     */
    public static void publish(RedisMessaging redis, String topic, String id, String payload) {
        redis.publish(
                topic,
                Map.of(
                        RedisStreamOutboxPublisher.FIELD_ID, id,
                        RedisStreamOutboxPublisher.FIELD_TOPIC, topic,
                        RedisStreamOutboxPublisher.FIELD_PAYLOAD, payload,
                        RedisStreamOutboxPublisher.FIELD_CREATED_AT,
                                Instant.now().toString()));
    }

    /**
     * 等到条件成立。
     *
     * @param description 条件的说明
     * @param condition 条件
     */
    public static void await(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("等待被中断：" + description);
            }
        }
        fail("等待超时：" + description);
    }

    /**
     * 以迁移角色读库（核对用，不受应用角色的权限限制）。
     *
     * @return JDBC 客户端
     */
    public static JdbcClient db() {
        return JdbcClient.create(TestDatabase.migratorDataSource());
    }

    /**
     * 一张表中某个 ID 的行数。
     *
     * @param table {@code usage_records} 或 {@code audit_logs}
     * @param id 消息 ID
     * @return 行数
     */
    public static long count(String table, String id) {
        return db().sql("SELECT count(*) FROM cp." + table + " WHERE id = :id")
                .param("id", id)
                .query(Long.class)
                .single();
    }
}
