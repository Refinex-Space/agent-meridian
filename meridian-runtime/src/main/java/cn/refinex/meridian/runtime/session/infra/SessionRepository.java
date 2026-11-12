package cn.refinex.meridian.runtime.session.infra;

import cn.refinex.meridian.common.context.Interaction;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import cn.refinex.meridian.runtime.session.domain.EndUserAttributes;
import cn.refinex.meridian.runtime.session.domain.NewSession;
import cn.refinex.meridian.runtime.session.domain.Session;
import cn.refinex.meridian.runtime.session.domain.SessionStatus;
import cn.refinex.meridian.runtime.stream.infra.EngineJson;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

/**
 * 会话表 {@code rt.sessions} 的项目级仓储：租户与项目来自当前 {@code PlatformContext}，读不到其他项目的会话
 * （架构设计「租户隔离」）。runtime 中经 {@code Blocking} 调用。
 *
 * <p>状态、{@code last_seq} 与终止时间只由事件写入入口随状态事件推进（{@code stream.app.SessionEventWriter}），这里不写。
 */
@Repository
public class SessionRepository extends TenantScopedRepository {

    private static final String COLUMNS = """
            id, tenant_id, project_id, agent_version_id, environment_id, environment_revision, end_user_id,
            interaction, status, last_seq, created_at, updated_at, custom_tools::text AS custom_tools,
            end_user_attributes::text AS end_user_attributes, metadata::text AS metadata, terminated_at, deleted_at,
            api_key_id
            """;

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public SessionRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 插入一个空闲会话，事件序号从 0 开始。创建它的 API Key 取自当前 {@code PlatformContext}（ADR-0048）。
     *
     * @param session 新建参数
     * @param now 当前时间，取自注入的 {@code Clock}
     */
    public void insert(NewSession session, Instant now) {
        sql("""
                        INSERT INTO rt.sessions (id, tenant_id, project_id, agent_version_id, environment_id,
                                                 environment_revision, end_user_id, end_user_attributes, interaction,
                                                 status, metadata, custom_tools, last_seq, last_activity_at,
                                                 created_at, updated_at, api_key_id)
                        VALUES (:id, :tenant_id, :project_id, :agent_version_id, :environment_id,
                                :environment_revision, :end_user_id, :end_user_attributes, :interaction,
                                'idle', :metadata, :custom_tools, 0, :now, :now, :now, :api_key)
                        """)
                .param("id", session.id())
                .param("agent_version_id", session.agentVersionId())
                .param("environment_id", session.environmentId())
                .param("environment_revision", session.environmentRevision())
                .param("end_user_id", session.endUserId())
                .param("end_user_attributes", JdbcValues.jsonbOf(session.endUserAttributes()))
                .param("interaction", session.interaction().name().toLowerCase(Locale.ROOT))
                .param("metadata", JdbcValues.jsonbOf(session.metadata()))
                .param("custom_tools", JdbcValues.jsonb(EngineJson.writeCustomTools(session.customTools())))
                .param("now", now)
                .param("api_key", PlatformContexts.current().apiKeyId())
                .update();
    }

    /**
     * 按 ID 读取当前项目中的会话，包括已删除但尚未清理的。
     *
     * @param sessionId 会话 ID
     * @return 会话；不存在或不属于当前项目时为空
     */
    public Optional<Session> find(String sessionId) {
        return sql("SELECT " + COLUMNS + """
                          FROM rt.sessions
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id
                        """)
                .param("id", sessionId)
                .query(SessionRepository::map)
                .optional();
    }

    /**
     * 列出当前项目中未删除的会话，新创建的在前（ID 为 ULID，按时间有序）。
     *
     * @param page 分页参数；游标的唯一键是上一页最后一个会话的 ID
     * @param endUserId 只列出该终端用户的会话；可为空，表示不过滤
     * @return 最多 {@code page.fetchSize()} 个会话
     */
    public List<Session> list(PageRequest page, String endUserId) {
        return sql("SELECT " + COLUMNS + """
                          FROM rt.sessions
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND deleted_at IS NULL
                           AND (CAST(:end_user_id AS text) IS NULL OR end_user_id = :end_user_id)
                           AND (CAST(:after_id AS text) IS NULL OR id < :after_id)
                         ORDER BY id DESC
                         LIMIT :limit
                        """)
                .param("end_user_id", endUserId)
                .param("after_id", page.after() == null ? null : page.after().key(0))
                .param("limit", page.fetchSize())
                .query(SessionRepository::map)
                .list();
    }

    /**
     * 整体替换会话的元数据。
     *
     * @param sessionId 会话 ID
     * @param metadata 新的元数据
     * @param now 当前时间
     * @return 更新成功时为 true；会话不存在或不属于当前项目时为 false
     */
    public boolean updateMetadata(String sessionId, Map<String, String> metadata, Instant now) {
        return sql("""
                                UPDATE rt.sessions SET metadata = :metadata, updated_at = :now
                                 WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id
                                """)
                        .param("metadata", JdbcValues.jsonbOf(metadata))
                        .param("now", now)
                        .param("id", sessionId)
                        .update()
                == 1;
    }

    /**
     * 记录删除时间；已删除的会话保持首次删除的时间。终止事件由调用方在同一事务中写入。
     *
     * @param sessionId 会话 ID
     * @param now 当前时间
     */
    public void markDeleted(String sessionId, Instant now) {
        sql("""
                        UPDATE rt.sessions SET deleted_at = COALESCE(deleted_at, :now), updated_at = :now
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id
                        """).param("now", now).param("id", sessionId).update();
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
                EngineJson.readCustomTools(rs.getString("custom_tools")),
                JsonMappers.lenient().readValue(rs.getString("end_user_attributes"), EndUserAttributes.class),
                JsonMappers.lenient().readValue(rs.getString("metadata"), STRING_MAP),
                JdbcValues.instant(rs, "terminated_at"),
                JdbcValues.instant(rs, "deleted_at"),
                rs.getString("api_key_id"));
    }
}
