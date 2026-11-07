package cn.refinex.meridian.persistence.outbox;

import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.jdbc.SchemaNames;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 在调用方的事务内写 outbox：消息与业务数据同时提交或同时回滚，回滚时消息不会被发布
 * （架构设计「跨服务数据流」）。
 *
 * <p>线程安全：无状态，可共享。
 */
@CrossTenant(reason = "outbox 表不按租户划分，消息载荷自带租户与项目；写入总在调用方的业务事务内发生")
public class OutboxWriter extends SystemRepository {

    private final String insertSql;
    private final Clock clock;

    /**
     * 创建 outbox 写入器。
     *
     * @param dataSource 应用的数据源
     * @param schema outbox 表所在的 schema（服务自己的 schema）
     * @param clock 时钟
     */
    public OutboxWriter(DataSource dataSource, String schema, Clock clock) {
        super(dataSource);
        this.insertSql = "INSERT INTO " + SchemaNames.require(schema)
                + ".outbox (id, topic, payload, created_at) VALUES (:id, :topic, :payload, :created_at)";
        this.clock = clock;
    }

    /**
     * 追加一条消息。必须在事务内调用，否则消息与业务数据不再原子。
     *
     * @param topic 主题，即目标 Redis Stream 的键
     * @param payload 消息载荷，序列化为 snake_case 的 JSON
     * @return 消息 ID
     * @throws IllegalStateException 当前线程没有活动的 Spring 事务时
     */
    public String append(String topic, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("OutboxWriter.append 必须在业务事务内调用");
        }
        String id = Ids.newUlid();
        jdbc().sql(insertSql)
                .param("id", id)
                .param("topic", topic)
                .param("payload", JdbcValues.jsonb(JsonMappers.lenient().writeValueAsString(payload)))
                .param("created_at", JdbcValues.timestamptz(clock.instant()))
                .update();
        return id;
    }
}
