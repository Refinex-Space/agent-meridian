package cn.refinex.meridian.runtime.approval.infra.system;

import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 待处理动作超时扫描的跨租户仓储（架构设计「待处理动作」）：每个节点定期扫描，用 {@code SKIP LOCKED} 保证多节点下同一个动作只被
 * 处理一次。
 */
@Repository
@CrossTenant(reason = "超时扫描跨租户查找已过截止时间的待处理动作，逐个在其所属项目的上下文中处理")
public class PendingActionTimeoutRepository extends SystemRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public PendingActionTimeoutRepository(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 锁住一个已过截止时间、仍在等待的动作，跳过被其他节点或回应事务锁住的行。
     *
     * @param now 当前时间
     * @param excluded 本次扫描中暂时跳过的动作（其线程正被其他事务锁住）
     * @return 超时的动作；没有时为空
     */
    public Optional<DueAction> lockNextDue(Instant now, Collection<String> excluded) {
        return jdbc().sql("""
                        SELECT id, tenant_id, project_id, session_id, thread_id, turn_id
                          FROM rt.pending_actions
                         WHERE status = 'pending' AND expires_at <= :now AND NOT (id = ANY (:excluded))
                         ORDER BY expires_at
                           FOR UPDATE SKIP LOCKED
                         LIMIT 1
                        """)
                .param("now", JdbcValues.timestamptz(now))
                .param("excluded", JdbcValues.textArray(List.copyOf(excluded)))
                .query((rs, row) -> new DueAction(
                        rs.getString("id"),
                        rs.getString("tenant_id"),
                        rs.getString("project_id"),
                        rs.getString("session_id"),
                        rs.getString("thread_id"),
                        rs.getString("turn_id")))
                .optional();
    }

    /**
     * 一个已过截止时间的待处理动作。
     *
     * @param actionId 动作 ID
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @param threadId 线程 ID
     * @param turnId 产生动作的轮次
     */
    public record DueAction(
            String actionId, String tenantId, String projectId, String sessionId, String threadId, String turnId) {}
}
