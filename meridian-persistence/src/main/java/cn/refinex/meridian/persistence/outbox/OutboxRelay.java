package cn.refinex.meridian.persistence.outbox;

import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.jdbc.SchemaNames;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * outbox 投递：批量取出未发布的消息、发布、标记为已发布。
 *
 * <p>多实例并发运行时不会重复投递同一条：取出用 {@code FOR UPDATE SKIP LOCKED}，被其他实例锁住的行直接跳过；
 * 发布与标记在同一个事务中完成，事务提交前锁一直持有。发布成功但事务提交失败时，消息会在下一轮再次发布，
 * 所以整体是至少一次投递，消费方按消息 ID 幂等。
 *
 * <p>一批中某条发布失败时，已成功的照常标记，失败的及其后的消息留到下一轮，保持大致的写入顺序。
 *
 * <p>线程安全：无状态，可被多个线程同时调用。
 */
@CrossTenant(reason = "outbox 投递是跨租户的后台作业，按消息 ID 顺序处理所有租户的消息")
public class OutboxRelay extends SystemRepository {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final String selectSql;
    private final String markSql;
    private final String purgeSql;
    private final TransactionTemplate transactions;
    private final OutboxPublisher publisher;
    private final Clock clock;
    private final int batchSize;

    /**
     * 创建 outbox 投递器。
     *
     * @param dataSource 应用的数据源
     * @param transactionManager 与数据源对应的事务管理器
     * @param schema outbox 表所在的 schema
     * @param publisher 发布目标
     * @param clock 时钟
     * @param batchSize 每批最多取出的消息数，大于 0
     */
    public OutboxRelay(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            String schema,
            OutboxPublisher publisher,
            Clock clock,
            int batchSize) {
        super(dataSource);
        String table = SchemaNames.require(schema) + ".outbox";
        this.selectSql = "SELECT id, topic, payload::text AS payload, created_at FROM " + table
                + " WHERE published_at IS NULL ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED";
        this.markSql = "UPDATE " + table + " SET published_at = :now WHERE id = ANY(:ids)";
        this.purgeSql = "DELETE FROM " + table + " WHERE id IN (SELECT id FROM " + table
                + " WHERE published_at < :before ORDER BY id LIMIT :limit)";
        this.transactions = new TransactionTemplate(transactionManager);
        this.publisher = publisher;
        this.clock = clock;
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize 必须大于 0：" + batchSize);
        }
        this.batchSize = batchSize;
    }

    /**
     * 投递一批消息。
     *
     * @return 本批成功发布并标记的消息数
     */
    public int relayOnce() {
        Integer published = transactions.execute(status -> {
            List<OutboxMessage> batch = jdbc().sql(selectSql)
                    .param("limit", batchSize)
                    .query((rs, row) -> new OutboxMessage(
                            rs.getString("id"),
                            rs.getString("topic"),
                            rs.getString("payload"),
                            JdbcValues.instant(rs, "created_at")))
                    .list();
            List<String> done = new ArrayList<>(batch.size());
            for (OutboxMessage message : batch) {
                try {
                    publisher.publish(message);
                    done.add(message.id());
                } catch (RuntimeException e) {
                    log.warn("outbox 消息发布失败，留到下一轮 id={} topic={}", message.id(), message.topic(), e);
                    break;
                }
            }
            if (!done.isEmpty()) {
                jdbc().sql(markSql)
                        .param("now", JdbcValues.timestamptz(clock.instant()))
                        .param("ids", done.toArray(String[]::new))
                        .update();
            }
            return done.size();
        });
        return published == null ? 0 : published;
    }

    /**
     * 连续投递，直到某一批不满或发布失败，用于定时任务每次唤醒时清空积压。
     *
     * @return 本次共发布的消息数
     */
    public int relayUntilDrained() {
        int total = 0;
        int published;
        do {
            published = relayOnce();
            total += published;
        } while (published == batchSize);
        return total;
    }

    /**
     * 删除发布时间早于指定时间点的消息，每次最多删除 {@code limit} 条，防止表无限增长。
     *
     * @param before 发布时间早于它的消息会被删除
     * @param limit 本次最多删除的条数
     * @return 删除的条数
     */
    public int purgePublishedBefore(Instant before, int limit) {
        return jdbc().sql(purgeSql)
                .param("before", JdbcValues.timestamptz(before))
                .param("limit", limit)
                .update();
    }
}
