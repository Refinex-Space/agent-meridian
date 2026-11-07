package cn.refinex.meridian.runtime.session.infra;

import cn.refinex.meridian.common.context.Interaction;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import cn.refinex.meridian.runtime.session.domain.NewSession;
import cn.refinex.meridian.runtime.session.domain.Session;
import cn.refinex.meridian.runtime.session.domain.SessionStatus;
import cn.refinex.meridian.runtime.stream.infra.EngineJson;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 会话表 {@code rt.sessions} 的项目级仓储：租户与项目来自当前 {@code PlatformContext}，读不到其他项目的会话
 * （架构设计「租户隔离」）。runtime 中经 {@code Blocking} 调用。
 */
@Repository
public class SessionRepository extends TenantScopedRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public SessionRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 插入一个空闲会话，事件序号从 0 开始。
     *
     * @param session 新建参数
     * @param now 当前时间，取自注入的 {@code Clock}
     */
    public void insert(NewSession session, Instant now) {
        sql("""
                        INSERT INTO rt.sessions (id, tenant_id, project_id, agent_version_id, environment_id,
                                                 environment_revision, end_user_id, interaction, status, last_seq,
                                                 last_activity_at, created_at, updated_at)
                        VALUES (:id, :tenant_id, :project_id, :agent_version_id, :environment_id,
                                :environment_revision, :end_user_id, :interaction, 'idle', 0, :now, :now, :now)
                        """)
                .param("id", session.id())
                .param("agent_version_id", session.agentVersionId())
                .param("environment_id", session.environmentId())
                .param("environment_revision", session.environmentRevision())
                .param("end_user_id", session.endUserId())
                .param("interaction", session.interaction().name().toLowerCase(Locale.ROOT))
                .param("now", now)
                .update();
    }

    /**
     * 按 ID 读取当前项目中的会话。
     *
     * @param sessionId 会话 ID
     * @return 会话；不存在或不属于当前项目时为空
     */
    public Optional<Session> find(String sessionId) {
        return sql("""
                        SELECT id, tenant_id, project_id, agent_version_id, environment_id, environment_revision,
                               end_user_id, interaction, status, last_seq, created_at, updated_at,
                               custom_tools::text AS custom_tools
                          FROM rt.sessions
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id
                        """).param("id", sessionId).query(SessionRepository::map).optional();
    }

    private static Session map(ResultSet rs, int row) throws SQLException {
        return new Session(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("project_id"),
                rs.getString("agent_version_id"),
                rs.getString("environment_id"),
                rs.getLong("environment_revision"),
                rs.getString("end_user_id"),
                Interaction.valueOf(rs.getString("interaction").toUpperCase(Locale.ROOT)),
                SessionStatus.of(rs.getString("status")),
                rs.getLong("last_seq"),
                JdbcValues.instant(rs, "created_at"),
                JdbcValues.instant(rs, "updated_at"),
                EngineJson.readCustomTools(rs.getString("custom_tools")));
    }
}
