package cn.refinex.meridian.runtime.sandbox.infra;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec.SandboxSize;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import cn.refinex.meridian.runtime.sandbox.domain.SandboxRecord;
import cn.refinex.meridian.runtime.sandbox.domain.SessionSandbox;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * {@code rt.sessions} 的沙箱列（项目级）：读写会话的沙箱、使用时间、快照键与计量起点，见架构设计「会话沙箱」。
 *
 * <p>状态转换都在调用方的事务里先 {@link #lock} 住会话行再写：获取沙箱、空闲回收、删除会话的沙箱彼此以行锁串行，回收器在同一把锁下
 * 确认没有活动轮次（{@link #hasActiveTurns}）。
 */
@Repository
public class SessionSandboxRepository extends TenantScopedRepository {

    private static final String COLUMNS = """
            id, agent_version_id, sandbox_state::text AS sandbox_state, sandbox_pod_ip, sandbox_last_used_at,
            sandbox_snapshot_key, sandbox_metered_at
            """;

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public SessionSandboxRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 读取会话的沙箱列，不加锁。
     *
     * @param sessionId 会话 ID
     * @return 沙箱列；会话不存在时为空
     */
    public Optional<SessionSandbox> find(String sessionId) {
        return sql("SELECT " + COLUMNS + """
                          FROM rt.sessions
                         WHERE id = :id AND tenant_id = :tenant_id AND project_id = :project_id
                        """)
                .param("id", sessionId)
                .query(SessionSandboxRepository::map)
                .optional();
    }

    /**
     * 以 {@code FOR UPDATE} 锁住会话行并读取沙箱列。
     *
     * @param sessionId 会话 ID
     * @param skipLocked 行被其他事务锁住时是否跳过（回收器用，不等待）
     * @return 沙箱列；会话不存在，或 {@code skipLocked} 时行被锁住，为空
     */
    public Optional<SessionSandbox> lock(String sessionId, boolean skipLocked) {
        return sql("SELECT " + COLUMNS + """
                          FROM rt.sessions
                         WHERE id = :id AND tenant_id = :tenant_id AND project_id = :project_id
                           FOR UPDATE
                        """ + (skipLocked ? " SKIP LOCKED" : ""))
                .param("id", sessionId)
                .query(SessionSandboxRepository::map)
                .optional();
    }

    /**
     * 会话是否有 {@code queued}、{@code running} 或 {@code awaiting_action} 的轮次（任一线程）。
     *
     * @param sessionId 会话 ID
     * @return 有活动轮次时为 true
     */
    public boolean hasActiveTurns(String sessionId) {
        return sql("""
                        SELECT EXISTS (SELECT 1 FROM rt.turns
                                        WHERE session_id = :id AND tenant_id = :tenant_id AND project_id = :project_id
                                          AND status IN ('queued', 'running', 'awaiting_action'))
                        """).param("id", sessionId).query(Boolean.class).single();
    }

    /**
     * 保存会话当前的沙箱，同时记为刚被使用。
     *
     * @param sessionId 会话 ID
     * @param sandbox 沙箱
     * @param podIp 沙箱 Pod 的 IP
     * @param now 当前时间，写入 {@code sandbox_last_used_at}
     * @param meteredAt 新的计量起点；为空时保持原值（重新连接已有沙箱）
     */
    public void saveSandbox(String sessionId, SandboxRecord sandbox, String podIp, Instant now, Instant meteredAt) {
        sql("""
                        UPDATE rt.sessions
                           SET sandbox_state = :state, sandbox_pod_ip = :pod_ip, sandbox_last_used_at = :now,
                               sandbox_metered_at = COALESCE(:metered_at, sandbox_metered_at), updated_at = :now
                         WHERE id = :id AND tenant_id = :tenant_id AND project_id = :project_id
                        """)
                .param("id", sessionId)
                .param("state", JdbcValues.jsonb(write(sandbox)))
                .param("pod_ip", podIp)
                .param("now", JdbcValues.timestamptz(now))
                .param("metered_at", meteredAt == null ? null : JdbcValues.timestamptz(meteredAt))
                .update();
    }

    /**
     * 更新 {@code sandbox_last_used_at}。
     *
     * @param sessionId 会话 ID
     * @param now 当前时间
     */
    public void markUsed(String sessionId, Instant now) {
        sql("""
                        UPDATE rt.sessions SET sandbox_last_used_at = :now
                         WHERE id = :id AND tenant_id = :tenant_id AND project_id = :project_id
                        """)
                .param("id", sessionId)
                .param("now", JdbcValues.timestamptz(now))
                .update();
    }

    /**
     * 沙箱已删除：清空沙箱与 Pod IP（同 IP 的新 Pod 不继承出网授权）、计量起点，记下回收留下的快照。
     *
     * @param sessionId 会话 ID
     * @param snapshotKey 新的快照键；为空时清空快照键
     * @param now 当前时间
     */
    public void clearSandbox(String sessionId, String snapshotKey, Instant now) {
        sql("""
                        UPDATE rt.sessions
                           SET sandbox_state = NULL, sandbox_pod_ip = NULL, sandbox_metered_at = NULL,
                               sandbox_snapshot_key = :snapshot_key, updated_at = :now
                         WHERE id = :id AND tenant_id = :tenant_id AND project_id = :project_id
                        """)
                .param("id", sessionId)
                .param("snapshot_key", snapshotKey)
                .param("now", JdbcValues.timestamptz(now))
                .update();
    }

    /**
     * 推进计量起点。
     *
     * @param sessionId 会话 ID
     * @param meteredAt 新的计量起点
     */
    public void meteredUntil(String sessionId, Instant meteredAt) {
        sql("""
                        UPDATE rt.sessions SET sandbox_metered_at = :metered_at
                         WHERE id = :id AND tenant_id = :tenant_id AND project_id = :project_id
                        """)
                .param("id", sessionId)
                .param("metered_at", JdbcValues.timestamptz(meteredAt))
                .update();
    }

    /**
     * 会话是否已删除（ADR-0049：已删除的会话不再获取沙箱，清理期间不会有新的沙箱）。在已锁住会话行的事务中调用。
     *
     * @param sessionId 会话 ID
     * @return 已删除时为 true
     */
    public boolean deleted(String sessionId) {
        return sql("""
                        SELECT deleted_at IS NOT NULL FROM rt.sessions
                         WHERE id = :id AND tenant_id = :tenant_id AND project_id = :project_id
                        """).param("id", sessionId).query(Boolean.class).optional().orElse(true);
    }

    private static SessionSandbox map(ResultSet rs, int row) throws SQLException {
        return new SessionSandbox(
                rs.getString("id"),
                rs.getString("agent_version_id"),
                read(rs.getString("sandbox_state")),
                rs.getString("sandbox_pod_ip"),
                JdbcValues.instant(rs, "sandbox_last_used_at"),
                rs.getString("sandbox_snapshot_key"),
                JdbcValues.instant(rs, "sandbox_metered_at"));
    }

    private static String write(SandboxRecord sandbox) {
        return JsonMappers.lenient()
                .writeValueAsString(
                        new Stored(sandbox.sandboxId(), sandbox.size().value(), sandbox.state()));
    }

    private static SandboxRecord read(String json) {
        if (json == null) {
            return null;
        }
        Stored stored = JsonMappers.lenient().readValue(json, Stored.class);
        SandboxSize size = Arrays.stream(SandboxSize.values())
                .filter(s -> s.value().equals(stored.size()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未知的沙箱规格：" + stored.size()));
        return new SandboxRecord(stored.sandboxId(), size, stored.state());
    }

    /**
     * {@code sandbox_state} 的存储格式（字段为 snake_case）。
     *
     * @param sandboxId 沙箱资源名
     * @param size 规格的取值
     * @param state 驱动序列化的沙箱状态
     */
    record Stored(String sandboxId, String size, String state) {}
}
