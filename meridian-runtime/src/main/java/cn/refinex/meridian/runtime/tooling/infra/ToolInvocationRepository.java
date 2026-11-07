package cn.refinex.meridian.runtime.tooling.infra;

import cn.refinex.meridian.engine.spi.event.BlobRef;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort.InvocationStart;
import cn.refinex.meridian.engine.spi.port.ToolLedgerPort.Status;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import cn.refinex.meridian.runtime.tooling.domain.ToolInvocation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 工具账本 {@code rt.tool_invocations} 的项目级仓储（架构设计「核心表」「崩溃恢复」）。方法都在调用方的事务中执行；带租约的写入
 * 由调用方先调用 {@link #lockLease}。
 */
@Repository
public class ToolInvocationRepository extends TenantScopedRepository {

    private static final String COLUMNS = """
            id, turn_id, thread_id, lease_epoch, tool_use_id, tool_name, side_effect, idempotency_key, status,
            input::text AS input, result_ref::text AS result_ref, started_at, finished_at
            """;

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public ToolInvocationRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 以共享锁锁住持有租约的轮次：{@code status='running' AND lease_owner = 本节点 AND lease_epoch = epoch}（ADR-0027 第 3 条）。
     *
     * @param turnId 轮次 ID（子线程的调用为父轮次）
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
     * 记录一次工具执行开始，状态为 {@code running}。
     *
     * @param id 账本记录 ID
     * @param sessionId 会话 ID
     * @param turnId 轮次 ID
     * @param epoch 发起调用的那次执行的 epoch
     * @param start 执行信息
     * @param now 当前时间
     */
    public void insertRunning(
            String id, String sessionId, String turnId, long epoch, InvocationStart start, Instant now) {
        sql("""
                        INSERT INTO rt.tool_invocations (id, tenant_id, project_id, session_id, turn_id, thread_id,
                                                         lease_epoch, tool_use_id, tool_name, side_effect,
                                                         idempotency_key, status, input, started_at)
                        VALUES (:id, :tenant_id, :project_id, :session_id, :turn_id, :thread_id, :epoch, :tool_use_id,
                                :tool_name, :side_effect, :idempotency_key, 'running', :input, :now)
                        """)
                .param("id", id)
                .param("session_id", sessionId)
                .param("turn_id", turnId)
                .param("thread_id", start.threadId())
                .param("epoch", epoch)
                .param("tool_use_id", start.toolUseId())
                .param("tool_name", start.toolName())
                .param("side_effect", start.sideEffect().value())
                .param("idempotency_key", start.idempotencyKey())
                .param("input", JdbcValues.jsonbOf(start.input()))
                .param("now", now)
                .update();
    }

    /**
     * 记录一次工具执行结束。只更新仍在执行中、且由该轮次发起的记录。
     *
     * @param id 账本记录 ID
     * @param turnId 轮次 ID
     * @param status 结束状态
     * @param resultRef 结果引用；可为空
     * @param now 当前时间
     * @return 更新成功时为 true；记录不存在、不属于该轮次或已经结束时为 false
     */
    public boolean finish(String id, String turnId, Status status, BlobRef resultRef, Instant now) {
        return sql("""
                                UPDATE rt.tool_invocations
                                   SET status = :status, result_ref = :result_ref, finished_at = :now
                                 WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id
                                   AND turn_id = :turn_id AND status = 'running'
                                """)
                        .param("status", value(status))
                        .param("result_ref", JdbcValues.jsonbOf(resultRef))
                        .param("now", now)
                        .param("id", id)
                        .param("turn_id", turnId)
                        .update()
                == 1;
    }

    /**
     * 读取一轮的全部账本记录（含各次执行），按开始时间排列。
     *
     * @param turnId 轮次 ID
     * @return 记录
     */
    public List<ToolInvocation> listByTurn(String turnId) {
        return sql("""
                        SELECT %s FROM rt.tool_invocations
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND turn_id = :turn_id
                         ORDER BY started_at, id
                        """.formatted(COLUMNS))
                .param("turn_id", turnId)
                .query(ToolInvocationRepository::map)
                .list();
    }

    /**
     * 按 ID 读取一条账本记录。
     *
     * @param id 账本记录 ID
     * @return 记录；不存在或不属于当前项目时为空
     */
    public Optional<ToolInvocation> find(String id) {
        return sql("""
                        SELECT %s FROM rt.tool_invocations
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id
                        """.formatted(COLUMNS))
                .param("id", id)
                .query(ToolInvocationRepository::map)
                .optional();
    }

    private static ToolInvocation map(ResultSet rs, int row) throws SQLException {
        String resultRef = rs.getString("result_ref");
        return new ToolInvocation(
                rs.getString("id"),
                rs.getString("turn_id"),
                rs.getString("thread_id"),
                rs.getLong("lease_epoch"),
                rs.getString("tool_use_id"),
                rs.getString("tool_name"),
                sideEffect(rs.getString("side_effect")),
                rs.getString("idempotency_key"),
                Status.valueOf(rs.getString("status").toUpperCase(Locale.ROOT)),
                rs.getString("input"),
                resultRef == null ? null : JdbcValues.readJsonb(rs, "result_ref", BlobRef.class),
                JdbcValues.instant(rs, "started_at"),
                JdbcValues.instant(rs, "finished_at"));
    }

    private static SideEffect sideEffect(String value) {
        return Arrays.stream(SideEffect.values())
                .filter(s -> s.value().equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未知的副作用等级：" + value));
    }

    private static String value(Status status) {
        return status.name().toLowerCase(Locale.ROOT);
    }
}
