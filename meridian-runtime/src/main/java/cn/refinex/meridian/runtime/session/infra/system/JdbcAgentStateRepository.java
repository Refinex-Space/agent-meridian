package cn.refinex.meridian.runtime.session.infra.system;

import cn.refinex.meridian.common.error.LeaseLostException;
import cn.refinex.meridian.engine.spi.port.AgentStateRepository;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import cn.refinex.meridian.runtime.dispatch.domain.NodeId;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link AgentStateRepository} 的实现，表为 {@code rt.agent_states}（架构设计「端口」「租约与 fencing」「崩溃恢复」）。
 *
 * <p>每次写入（{@code save}、{@code saveIfVersion}、{@code delete}、{@code deleteAll}）都在一个事务里先锁住持有租约的轮次：
 * {@code rt.turns} 中 {@code thread_id = 持有租约的线程 AND status='running' AND lease_owner = 本节点 AND lease_epoch = epoch}，
 * 以 {@code FOR SHARE} 读取，使回收器与置回队列的更新必须等本事务提交；条件不满足时抛 {@link LeaseLostException}，什么都不写。
 * 租户与项目取自该轮次，所以写入不依赖调用线程上的 {@code PlatformContext}（AgentScope 在自己的线程上调用状态存储）。
 *
 * <p>{@code content_epoch}：内容写入时更新为当前 epoch；权限上下文的安装写入（{@code StateFence.contentWrite} 为 false）保留原值。
 *
 * <p>方法是同步的、会阻塞：AgentScope 的状态存储接口是同步接口，它在 {@code boundedElastic} 或停机监控线程上调用，
 * 平台自己的调用经 {@code Blocking}。线程安全：无状态。
 */
@Repository
@CrossTenant(reason = "AgentStateRepository 端口实现：按线程 ID 隔离，线程 ID 只能经租约获得，租约只对本租户的轮次发放；租户与项目取自持有租约的轮次")
public class JdbcAgentStateRepository extends SystemRepository implements AgentStateRepository {

    private final TransactionTemplate transactions;
    private final NodeId nodeId;
    private final Clock clock;

    /**
     * 创建状态仓储。
     *
     * @param dataSource 应用数据源
     * @param transactions 事务模板（传播行为 REQUIRED）
     * @param nodeId 本节点 ID，租约条件中的 {@code lease_owner}
     * @param clock 时钟
     */
    public JdbcAgentStateRepository(
            DataSource dataSource, TransactionTemplate transactions, NodeId nodeId, Clock clock) {
        super(dataSource);
        this.transactions = transactions;
        this.nodeId = nodeId;
        this.clock = clock;
    }

    @Override
    public Optional<StoredState> find(StateKey key) {
        return jdbc().sql("""
                        SELECT state, version FROM rt.agent_states
                         WHERE user_id = :user_id AND thread_id = :thread_id AND state_key = :state_key
                        """)
                .param("user_id", key.userId())
                .param("thread_id", key.threadId())
                .param("state_key", key.key())
                .query((rs, row) -> new StoredState(rs.getString("state"), rs.getLong("version")))
                .optional();
    }

    @Override
    public long save(StateKey key, String json, StateFence fence) {
        return fenced(
                fence,
                scope -> jdbc().sql("""
                        INSERT INTO rt.agent_states AS s (user_id, thread_id, state_key, tenant_id, project_id, state,
                                                          version, content_epoch, created_at, updated_at)
                        VALUES (:user_id, :thread_id, :state_key, :tenant_id, :project_id, :state,
                                1, :content_epoch, :now, :now)
                        ON CONFLICT (user_id, thread_id, state_key) DO UPDATE
                           SET state = EXCLUDED.state, version = s.version + 1,
                               content_epoch = COALESCE(EXCLUDED.content_epoch, s.content_epoch),
                               updated_at = EXCLUDED.updated_at
                        RETURNING version
                        """)
                        .param("user_id", key.userId())
                        .param("thread_id", key.threadId())
                        .param("state_key", key.key())
                        .param("tenant_id", scope.tenantId())
                        .param("project_id", scope.projectId())
                        .param("state", JdbcValues.jsonb(json))
                        .param("content_epoch", contentEpoch(fence))
                        .param("now", JdbcValues.timestamptz(scope.now()))
                        .query(Long.class)
                        .single());
    }

    @Override
    public OptionalLong saveIfVersion(StateKey key, String json, long expectedVersion, StateFence fence) {
        return fenced(fence, scope -> {
            Optional<Long> written = expectedVersion == 0
                    ? insertIfAbsent(key, json, fence, scope)
                    : updateIfVersion(key, json, expectedVersion, fence, scope);
            return written.map(OptionalLong::of).orElseGet(OptionalLong::empty);
        });
    }

    @Override
    public boolean exists(String userId, String threadId) {
        return jdbc().sql("""
                        SELECT EXISTS (SELECT 1 FROM rt.agent_states WHERE user_id = :user_id AND thread_id = :thread_id)
                        """)
                .param("user_id", userId)
                .param("thread_id", threadId)
                .query(Boolean.class)
                .single();
    }

    @Override
    public void delete(StateKey key, StateFence fence) {
        fenced(
                fence,
                scope -> jdbc().sql("""
                        DELETE FROM rt.agent_states
                         WHERE user_id = :user_id AND thread_id = :thread_id AND state_key = :state_key
                        """)
                        .param("user_id", key.userId())
                        .param("thread_id", key.threadId())
                        .param("state_key", key.key())
                        .update());
    }

    @Override
    public void deleteAll(String userId, String threadId, StateFence fence) {
        fenced(
                fence,
                scope -> jdbc().sql("DELETE FROM rt.agent_states WHERE user_id = :user_id AND thread_id = :thread_id")
                        .param("user_id", userId)
                        .param("thread_id", threadId)
                        .update());
    }

    @Override
    public Set<String> listThreadIds(String userId) {
        return Set.copyOf(jdbc().sql("SELECT DISTINCT thread_id FROM rt.agent_states WHERE user_id = :user_id")
                .param("user_id", userId)
                .query(String.class)
                .list());
    }

    @Override
    public OptionalLong contentEpoch(String userId, String threadId) {
        Long epoch = jdbc().sql("""
                        SELECT max(content_epoch) FROM rt.agent_states WHERE user_id = :user_id AND thread_id = :thread_id
                        """)
                .param("user_id", userId)
                .param("thread_id", threadId)
                .query(Long.class)
                .optional()
                .orElse(null);
        return epoch == null ? OptionalLong.empty() : OptionalLong.of(epoch);
    }

    private Optional<Long> insertIfAbsent(StateKey key, String json, StateFence fence, LeaseScope scope) {
        return jdbc().sql("""
                        INSERT INTO rt.agent_states (user_id, thread_id, state_key, tenant_id, project_id, state,
                                                     version, content_epoch, created_at, updated_at)
                        VALUES (:user_id, :thread_id, :state_key, :tenant_id, :project_id, :state,
                                1, :content_epoch, :now, :now)
                        ON CONFLICT (user_id, thread_id, state_key) DO NOTHING
                        RETURNING version
                        """)
                .param("user_id", key.userId())
                .param("thread_id", key.threadId())
                .param("state_key", key.key())
                .param("tenant_id", scope.tenantId())
                .param("project_id", scope.projectId())
                .param("state", JdbcValues.jsonb(json))
                .param("content_epoch", contentEpoch(fence))
                .param("now", JdbcValues.timestamptz(scope.now()))
                .query(Long.class)
                .optional();
    }

    private Optional<Long> updateIfVersion(
            StateKey key, String json, long expectedVersion, StateFence fence, LeaseScope scope) {
        return jdbc().sql("""
                        UPDATE rt.agent_states
                           SET state = :state, version = version + 1,
                               content_epoch = COALESCE(:content_epoch, content_epoch), updated_at = :now
                         WHERE user_id = :user_id AND thread_id = :thread_id AND state_key = :state_key
                           AND version = :expected_version
                        RETURNING version
                        """)
                .param("state", JdbcValues.jsonb(json))
                .param("content_epoch", contentEpoch(fence))
                .param("now", JdbcValues.timestamptz(scope.now()))
                .param("user_id", key.userId())
                .param("thread_id", key.threadId())
                .param("state_key", key.key())
                .param("expected_version", expectedVersion)
                .query(Long.class)
                .optional();
    }

    /** 在一个事务里先锁住租约，再执行写入；租约条件不满足时抛出，事务回滚。 */
    private <T> T fenced(StateFence fence, Function<LeaseScope, T> write) {
        return transactions.execute(status -> write.apply(lockLease(fence)));
    }

    private LeaseScope lockLease(StateFence fence) {
        Instant now = clock.instant();
        return jdbc().sql("""
                        SELECT tenant_id, project_id FROM rt.turns
                         WHERE thread_id = :lease_thread_id AND status = 'running'
                           AND lease_owner = :node_id AND lease_epoch = :epoch
                           FOR SHARE
                        """)
                .param("lease_thread_id", fence.leaseThreadId())
                .param("node_id", nodeId.value())
                .param("epoch", fence.epoch())
                .query((rs, row) -> new LeaseScope(rs.getString("tenant_id"), rs.getString("project_id"), now))
                .optional()
                .orElseThrow(() -> new LeaseLostException("线程 " + fence.leaseThreadId() + " 在本节点 " + nodeId
                        + " 的租约不是 epoch " + fence.epoch() + "，拒绝写入智能体状态"));
    }

    private static Long contentEpoch(StateFence fence) {
        return fence.contentWrite() ? fence.epoch() : null;
    }

    /** 持有租约的轮次所在的租户与项目，以及本次写入的时间。 */
    private record LeaseScope(String tenantId, String projectId, Instant now) {}
}
