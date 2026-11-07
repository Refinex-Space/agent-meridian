package cn.refinex.meridian.runtime.delivery.infra;

import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import cn.refinex.meridian.runtime.delivery.domain.InboxEntry;
import cn.refinex.meridian.runtime.delivery.domain.TakenMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 收件箱 {@code rt.inbox_messages} 的项目级仓储（架构设计「消息投递」「崩溃恢复」）。方法都在调用方的事务中执行。
 *
 * <p>取出消息时同时读出对应 {@code user.message} 事件的内容（收件箱只记录投递状态）。取出的顺序由调用方按
 * {@link TakenMessage#ARRIVAL_ORDER} 排列：{@code UPDATE ... RETURNING} 不保证顺序。
 */
@Repository
public class InboxRepository extends TenantScopedRepository {

    /** 取出消息时返回的列：记录、事件 ID、事件内容与写入时间。 */
    private static final String TAKEN_COLUMNS = """
            i.id, i.event_id, i.created_at,
            (SELECT e.data::text FROM rt.session_events e
              WHERE e.session_id = i.session_id AND e.id = i.event_id) AS event_data
            """;

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public InboxRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 写入一条待投递的消息。
     *
     * @param entry 消息
     * @param now 当前时间
     * @return 收件箱记录 ID（ULID）
     */
    public String insert(InboxEntry entry, Instant now) {
        String id = Ids.newUlid();
        sql("""
                        INSERT INTO rt.inbox_messages (id, tenant_id, project_id, session_id, thread_id, event_id, delivery,
                                                       status, created_at)
                        VALUES (:id, :tenant_id, :project_id, :session_id, :thread_id, :event_id, :delivery,
                                'pending', :now)
                        """)
                .param("id", id)
                .param("session_id", entry.sessionId())
                .param("thread_id", entry.threadId())
                .param("event_id", entry.eventId())
                .param("delivery", entry.delivery().value())
                .param("now", now)
                .update();
        return id;
    }

    /**
     * 以共享锁锁住持有租约的轮次：{@code status='running' AND lease_owner = 本节点 AND lease_epoch = epoch}。带租约的收件箱写入
     * 先调用它，写入事务提交前租约不会被回收（ADR-0027 第 3 条）。
     *
     * @param turnId 轮次 ID
     * @param nodeId 本节点 ID
     * @param epoch 租约 epoch
     * @return 租约条件满足时为 true
     */
    public boolean lockLease(String turnId, String nodeId, long epoch) {
        return sql("""
                        SELECT 1 FROM rt.turns
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :turn_id
                           AND status = 'running' AND lease_owner = :node_id AND lease_epoch = :epoch
                           FOR SHARE
                        """)
                .param("turn_id", turnId)
                .param("node_id", nodeId)
                .param("epoch", epoch)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * 取出线程中待投递的 steer 消息，记录消费它们的轮次与执行 epoch。
     *
     * @param threadId 线程 ID
     * @param turnId 消费它们的轮次
     * @param epoch 消费它们的那次执行的 epoch
     * @param now 当前时间
     * @return 取出的消息，顺序未定
     */
    public List<TakenMessage> consumeSteer(String threadId, String turnId, long epoch, Instant now) {
        return sql("""
                        UPDATE rt.inbox_messages i
                           SET status = 'consumed', consumed_turn_id = :turn_id, consumed_epoch = :epoch,
                               consumed_at = :now
                         WHERE i.tenant_id = :tenant_id AND i.project_id = :project_id AND i.thread_id = :thread_id
                           AND i.status = 'pending' AND i.delivery = 'steer'
                        RETURNING %s
                        """.formatted(TAKEN_COLUMNS))
                .param("turn_id", turnId)
                .param("epoch", epoch)
                .param("now", now)
                .param("thread_id", threadId)
                .query(InboxRepository::mapTaken)
                .list();
    }

    /**
     * 取出线程中全部待投递的消息（queue，以及没来得及注入、降级为 queue 的 steer），记为被下一轮消费。它们进入下一轮的
     * 输入（{@code turn_inputs}），恢复时随输入重放，所以不记录执行 epoch。
     *
     * @param threadId 线程 ID
     * @param nextTurnId 合并它们的下一轮
     * @param now 当前时间
     * @return 取出的消息，顺序未定
     */
    public List<TakenMessage> takePending(String threadId, String nextTurnId, Instant now) {
        return sql("""
                        UPDATE rt.inbox_messages i
                           SET status = 'consumed', consumed_turn_id = :turn_id, consumed_epoch = NULL,
                               consumed_at = :now
                         WHERE i.tenant_id = :tenant_id AND i.project_id = :project_id AND i.thread_id = :thread_id
                           AND i.status = 'pending'
                        RETURNING %s
                        """.formatted(TAKEN_COLUMNS))
                .param("turn_id", nextTurnId)
                .param("now", now)
                .param("thread_id", threadId)
                .query(InboxRepository::mapTaken)
                .list();
    }

    /**
     * 重新投递被失败那次执行消费的 steer 消息：恢复为待投递（架构设计「崩溃恢复」）。
     *
     * @param turnId 轮次 ID
     * @param failedEpoch 失败那次执行的 epoch
     * @return 重新投递的条数
     */
    public int redeliverSteer(String turnId, long failedEpoch) {
        return sql("""
                        UPDATE rt.inbox_messages
                           SET status = 'pending', consumed_turn_id = NULL, consumed_epoch = NULL, consumed_at = NULL
                         WHERE tenant_id = :tenant_id AND project_id = :project_id
                           AND consumed_turn_id = :turn_id AND consumed_epoch = :epoch AND delivery = 'steer'
                        """).param("turn_id", turnId).param("epoch", failedEpoch).update();
    }

    private static TakenMessage mapTaken(ResultSet rs, int row) throws SQLException {
        return new TakenMessage(
                rs.getString("id"),
                rs.getString("event_id"),
                rs.getString("event_data"),
                JdbcValues.instant(rs, "created_at"));
    }
}
