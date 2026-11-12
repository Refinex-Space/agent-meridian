package cn.refinex.meridian.persistence.outbox;

import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.jdbc.SchemaNames;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * outbox 投递：认领一批未发布的消息、在数据库事务之外发布、标记为已发布（ADR-0048 第 8 条）。
 *
 * <p>认领是一条 {@code FOR UPDATE SKIP LOCKED} 的短事务，给消息写上认领截止时间（{@link #CLAIM_DURATION}）后立即提交；发布在事务外
 * 进行，Redis 变慢不会占着数据库连接与行锁。多实例并发运行时同一条消息不会被两个实例同时认领；实例在发布后、标记前崩溃，认领过期后
 * 消息被重新投递，所以整体是至少一次投递，消费方按消息 ID 幂等。
 *
 * <p>一条消息发布失败时，它的失败次数加一、认领被释放，同批后面的消息照常发布（一条坏消息不阻塞整个队列）；连续
 * {@link #MAX_CONSECUTIVE_FAILURES} 条失败说明发布目标不可用，结束本批。失败 {@link #MAX_ATTEMPTS} 次的消息不再自动投递，留在表中
 * 等人工处理。
 *
 * <p>线程安全：无状态，可被多个线程同时调用。
 */
@CrossTenant(reason = "outbox 投递是跨租户的后台作业，按消息 ID 顺序处理所有租户的消息")
public class OutboxRelay extends SystemRepository {

    /** 认领的有效期：远大于一批消息的发布耗时，过期未标记的消息由任一实例重新认领。 */
    public static final Duration CLAIM_DURATION = Duration.ofSeconds(30);

    /** 一条消息最多尝试发布的次数，达到后不再自动投递。 */
    public static final int MAX_ATTEMPTS = 10;

    /** 同一批中连续失败达到这个数就结束本批：发布目标多半不可用。 */
    static final int MAX_CONSECUTIVE_FAILURES = 3;

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final String claimSql;
    private final String markSql;
    private final String failSql;
    private final String releaseSql;
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
     * @param batchSize 每批最多认领的消息数，大于 0
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
        this.claimSql = "UPDATE " + table + " SET claimed_until = :until WHERE id IN (SELECT id FROM " + table
                + " WHERE published_at IS NULL AND attempts < :max_attempts"
                + " AND (claimed_until IS NULL OR claimed_until < :now)"
                + " ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED)"
                + " RETURNING id, topic, payload::text AS payload, created_at";
        this.markSql = "UPDATE " + table + " SET published_at = :now, claimed_until = NULL WHERE id = ANY(:ids)";
        this.failSql = "UPDATE " + table
                + " SET attempts = attempts + 1, claimed_until = NULL WHERE id = :id RETURNING attempts";
        this.releaseSql = "UPDATE " + table + " SET claimed_until = NULL WHERE id = ANY(:ids)";
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
        return relayBatch().published();
    }

    /**
     * 连续投递，直到某一批没有认满或发布目标不可用，用于定时任务每次唤醒时清空积压。
     *
     * @return 本次共发布的消息数
     */
    public int relayUntilDrained() {
        int total = 0;
        BatchResult batch;
        do {
            batch = relayBatch();
            total += batch.published();
        } while (batch.claimed() == batchSize && !batch.stopped());
        return total;
    }

    private BatchResult relayBatch() {
        List<OutboxMessage> batch = claim();
        List<String> done = new ArrayList<>(batch.size());
        int consecutiveFailures = 0;
        for (int i = 0; i < batch.size(); i++) {
            OutboxMessage message = batch.get(i);
            try {
                publisher.publish(message);
                done.add(message.id());
                consecutiveFailures = 0;
            } catch (RuntimeException e) {
                recordFailure(message, e);
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    release(batch.subList(i + 1, batch.size()));
                    break;
                }
            }
        }
        if (!done.isEmpty()) {
            jdbc().sql(markSql)
                    .param("now", JdbcValues.timestamptz(clock.instant()))
                    .param("ids", done.toArray(String[]::new))
                    .update();
        }
        return new BatchResult(batch.size(), done.size(), consecutiveFailures >= MAX_CONSECUTIVE_FAILURES);
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

    /** 在短事务中认领一批，按消息 ID 排序（{@code RETURNING} 不保证顺序）。 */
    private List<OutboxMessage> claim() {
        Instant now = clock.instant();
        List<OutboxMessage> claimed = transactions.execute(status -> jdbc().sql(claimSql)
                .param("until", JdbcValues.timestamptz(now.plus(CLAIM_DURATION)))
                .param("now", JdbcValues.timestamptz(now))
                .param("max_attempts", MAX_ATTEMPTS)
                .param("limit", batchSize)
                .query((rs, row) -> new OutboxMessage(
                        rs.getString("id"),
                        rs.getString("topic"),
                        rs.getString("payload"),
                        JdbcValues.instant(rs, "created_at")))
                .list());
        if (claimed == null) {
            return List.of();
        }
        return claimed.stream().sorted(Comparator.comparing(OutboxMessage::id)).toList();
    }

    private void recordFailure(OutboxMessage message, RuntimeException error) {
        int attempts = jdbc().sql(failSql)
                .param("id", message.id())
                .query(Integer.class)
                .single();
        if (attempts >= MAX_ATTEMPTS) {
            log.error(
                    "outbox 消息连续 {} 次发布失败，停止自动投递，需要人工处理 id={} topic={}",
                    attempts,
                    message.id(),
                    message.topic(),
                    error);
        } else {
            log.warn("outbox 消息发布失败，留到下一轮 id={} topic={} attempts={}", message.id(), message.topic(), attempts, error);
        }
    }

    private void release(List<OutboxMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        jdbc().sql(releaseSql)
                .param("ids", messages.stream().map(OutboxMessage::id).toArray(String[]::new))
                .update();
    }

    /**
     * 一批的结果。
     *
     * @param claimed 认领到的消息数
     * @param published 成功发布的消息数
     * @param stopped 是否因连续失败提前结束
     */
    private record BatchResult(int claimed, int published, boolean stopped) {}
}
