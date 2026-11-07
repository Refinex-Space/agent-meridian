package cn.refinex.meridian.runtime.session.infra;

import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import cn.refinex.meridian.runtime.session.domain.NewThread;
import cn.refinex.meridian.runtime.session.domain.SessionStatus;
import cn.refinex.meridian.runtime.session.domain.SessionThread;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 线程表 {@code rt.threads} 的项目级仓储，租户与项目来自当前 {@code PlatformContext}（架构设计「租户隔离」）。
 */
@Repository
public class ThreadRepository extends TenantScopedRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public ThreadRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 插入一个空闲线程。会话必须属于当前项目：插入语句从会话行取租户与项目，会话不在当前项目时插入 0 行。
     *
     * @param thread 新建参数
     * @param now 当前时间，取自注入的 {@code Clock}
     * @return 插入成功时为 true；会话不存在或不属于当前项目时为 false
     */
    public boolean insert(NewThread thread, Instant now) {
        return sql("""
                        INSERT INTO rt.threads (id, tenant_id, project_id, session_id, parent_thread_id,
                                                parent_tool_use_id, agent_name, status, created_at, updated_at)
                        SELECT :id, s.tenant_id, s.project_id, s.id, :parent_thread_id, :parent_tool_use_id,
                               :agent_name, 'idle', :now, :now
                          FROM rt.sessions s
                         WHERE s.tenant_id = :tenant_id AND s.project_id = :project_id AND s.id = :session_id
                        """)
                        .param("id", thread.id())
                        .param("session_id", thread.sessionId())
                        .param("parent_thread_id", thread.parentThreadId())
                        .param("parent_tool_use_id", thread.parentToolUseId())
                        .param("agent_name", thread.agentName())
                        .param("now", now)
                        .update()
                == 1;
    }

    /**
     * 按 ID 读取当前项目中的线程。
     *
     * @param threadId 线程 ID
     * @return 线程；不存在或不属于当前项目时为空
     */
    public Optional<SessionThread> find(String threadId) {
        return sql("""
                        SELECT id, tenant_id, project_id, session_id, parent_thread_id, parent_tool_use_id,
                               agent_name, status, created_at
                          FROM rt.threads
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id
                        """).param("id", threadId).query(ThreadRepository::map).optional();
    }

    /**
     * 列出会话的全部线程，主线程在前，其余按创建顺序（ID 为 ULID）。
     *
     * @param sessionId 会话 ID
     * @return 线程；会话不存在或不属于当前项目时为空列表
     */
    public List<SessionThread> listBySession(String sessionId) {
        return sql("""
                        SELECT id, tenant_id, project_id, session_id, parent_thread_id, parent_tool_use_id,
                               agent_name, status, created_at
                          FROM rt.threads
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND session_id = :session_id
                         ORDER BY parent_thread_id IS NOT NULL, id
                        """)
                .param("session_id", sessionId)
                .query(ThreadRepository::map)
                .list();
    }

    /**
     * 读取会话的主线程。
     *
     * @param sessionId 会话 ID
     * @return 主线程；会话不存在或不属于当前项目时为空
     */
    public Optional<SessionThread> findMain(String sessionId) {
        return sql("""
                        SELECT id, tenant_id, project_id, session_id, parent_thread_id, parent_tool_use_id,
                               agent_name, status, created_at
                          FROM rt.threads
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND session_id = :session_id
                           AND parent_thread_id IS NULL
                        """)
                .param("session_id", sessionId)
                .query(ThreadRepository::map)
                .optional();
    }

    /**
     * 按创建顺序分页列出会话的线程（ID 为 ULID；主线程随会话一起创建，排在最前）。
     *
     * @param sessionId 会话 ID
     * @param page 分页参数；游标的唯一键是上一页最后一个线程的 ID
     * @return 最多 {@code page.fetchSize()} 个线程
     */
    public List<SessionThread> page(String sessionId, PageRequest page) {
        return sql("""
                        SELECT id, tenant_id, project_id, session_id, parent_thread_id, parent_tool_use_id,
                               agent_name, status, created_at
                          FROM rt.threads
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND session_id = :session_id
                           AND (CAST(:after_id AS text) IS NULL OR id > :after_id)
                         ORDER BY id
                         LIMIT :limit
                        """)
                .param("session_id", sessionId)
                .param("after_id", page.after() == null ? null : page.after().key(0))
                .param("limit", page.fetchSize())
                .query(ThreadRepository::map)
                .list();
    }

    private static SessionThread map(ResultSet rs, int row) throws SQLException {
        return new SessionThread(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("project_id"),
                rs.getString("session_id"),
                rs.getString("parent_thread_id"),
                rs.getString("parent_tool_use_id"),
                rs.getString("agent_name"),
                SessionStatus.of(rs.getString("status")),
                JdbcValues.instant(rs, "created_at"));
    }
}
