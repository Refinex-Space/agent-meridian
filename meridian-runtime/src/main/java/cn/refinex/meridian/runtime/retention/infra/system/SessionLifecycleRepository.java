package cn.refinex.meridian.runtime.retention.infra.system;

import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 数据生命周期作业的跨租户仓储（架构设计「数据保留」，ADR-0049）：扫描已删除、该归档、有事件过期的会话，以 {@code FOR UPDATE SKIP
 * LOCKED} 锁行重新判定，删除会话数据、事件与工作区条目，更新归档与保留的列。方法在调用方的事务中执行。
 */
@Repository
@CrossTenant(reason = "会话清理、事件归档与保留清理是跨租户扫描的后台作业；租户与项目取自会话行，作业据此建立 PlatformContext")
public class SessionLifecycleRepository extends SystemRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public SessionLifecycleRepository(DataSource dataSource) {
        super(dataSource);
    }

    // ---------------------------------------------------------------------------------------------
    // 会话清理
    // ---------------------------------------------------------------------------------------------

    /**
     * 已删除、等待清理的会话，删除最早的在前。
     *
     * @param limit 至多条数
     * @return 会话
     */
    public List<DeletedSession> deletedSessions(int limit) {
        return jdbc().sql("""
                        SELECT id, tenant_id, project_id, deleted_at FROM rt.sessions
                         WHERE deleted_at IS NOT NULL
                         ORDER BY deleted_at
                         LIMIT :limit
                        """)
                .param("limit", limit)
                .query((rs, row) -> new DeletedSession(
                        rs.getString("id"),
                        rs.getString("tenant_id"),
                        rs.getString("project_id"),
                        JdbcValues.instant(rs, "deleted_at")))
                .list();
    }

    /**
     * 锁住一个已删除的会话行；被其他事务锁住（别的节点正在清理、仍有写入）时跳过。
     *
     * @param sessionId 会话 ID
     * @return 锁住时为 true
     */
    public boolean lockDeleted(String sessionId) {
        return jdbc().sql("""
                        SELECT 1 FROM rt.sessions WHERE id = :id AND deleted_at IS NOT NULL FOR UPDATE SKIP LOCKED
                        """)
                .param("id", sessionId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * 会话工作区中存放在对象存储的大内容的键（命名空间 {@code [tenant, project, end_user, session, …]}）。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @return 对象键
     */
    public List<String> workspaceObjectKeys(String tenantId, String projectId, String sessionId) {
        return jdbc().sql("""
                        SELECT object_key FROM rt.workspace_entries
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND namespace[4] = :session_id
                           AND object_key IS NOT NULL
                        """)
                .param("tenant_id", tenantId)
                .param("project_id", projectId)
                .param("session_id", sessionId)
                .query(String.class)
                .list();
    }

    /**
     * 删除会话的工作区条目（登记事项 D30）。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param sessionId 会话 ID
     * @return 删除的条数
     */
    public int deleteWorkspace(String tenantId, String projectId, String sessionId) {
        return jdbc().sql("""
                        DELETE FROM rt.workspace_entries
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND namespace[4] = :session_id
                        """)
                .param("tenant_id", tenantId)
                .param("project_id", projectId)
                .param("session_id", sessionId)
                .update();
    }

    /**
     * 删除会话行：线程、轮次与输入、事件、收件箱、智能体状态、工具账本、待处理动作、会话级权限规则、产物清单、Webhook 的 outbox 与投递
     * 记录都经外键级联删除。
     *
     * @param sessionId 会话 ID
     * @return 删除的行数（0 或 1）
     */
    public int deleteSession(String sessionId) {
        return jdbc().sql("DELETE FROM rt.sessions WHERE id = :id AND deleted_at IS NOT NULL")
                .param("id", sessionId)
                .update();
    }

    // ---------------------------------------------------------------------------------------------
    // 事件归档
    // ---------------------------------------------------------------------------------------------

    /**
     * 该归档的会话：未删除、最近一次活动早于截止时间、数据库中还有未归档的事件、没有未结束的轮次。最久无活动的在前。
     *
     * @param cutoff 截止时间（当前时间减 90 天）
     * @param limit 至多条数
     * @return 会话 ID
     */
    public List<String> archiveCandidates(Instant cutoff, int limit) {
        return jdbc().sql("""
                        SELECT s.id FROM rt.sessions s
                         WHERE s.deleted_at IS NULL AND s.last_activity_at < :cutoff
                           AND s.last_seq > COALESCE(s.events_archived_seq, 0)
                           AND NOT EXISTS (SELECT 1 FROM rt.turns t
                                            WHERE t.session_id = s.id
                                              AND t.status IN ('queued', 'running', 'awaiting_action'))
                         ORDER BY s.last_activity_at
                         LIMIT :limit
                        """)
                .param("cutoff", JdbcValues.timestamptz(cutoff))
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    /**
     * 锁住会话行并重新判定是否该归档（条件同 {@link #archiveCandidates}）。持锁期间同一会话的事件写入等待（seq 分配要锁会话行）。
     *
     * @param sessionId 会话 ID
     * @param cutoff 截止时间
     * @return 会话的归档状态；不再满足条件或行被锁住时为空
     */
    public Optional<ArchiveState> lockForArchive(String sessionId, Instant cutoff) {
        return jdbc().sql("""
                        SELECT s.id, s.tenant_id, s.project_id, s.last_seq, s.events_archive_key,
                               COALESCE(s.events_archived_seq, 0) AS archived_seq, s.events_archive_from
                          FROM rt.sessions s
                         WHERE s.id = :id AND s.deleted_at IS NULL AND s.last_activity_at < :cutoff
                           AND s.last_seq > COALESCE(s.events_archived_seq, 0)
                           AND NOT EXISTS (SELECT 1 FROM rt.turns t
                                            WHERE t.session_id = s.id
                                              AND t.status IN ('queued', 'running', 'awaiting_action'))
                           FOR UPDATE OF s SKIP LOCKED
                        """)
                .param("id", sessionId)
                .param("cutoff", JdbcValues.timestamptz(cutoff))
                .query(SessionLifecycleRepository::archiveState)
                .optional();
    }

    /**
     * 删除数据库中 seq 不超过上限的事件（已写进归档文件）。
     *
     * @param sessionId 会话 ID
     * @param maxSeq 上限（含）
     * @return 删除的条数
     */
    public int deleteEventsUpTo(String sessionId, long maxSeq) {
        return jdbc().sql("DELETE FROM rt.session_events WHERE session_id = :session_id AND seq <= :max_seq")
                .param("session_id", sessionId)
                .param("max_seq", maxSeq)
                .update();
    }

    /**
     * 记录归档结果。
     *
     * @param sessionId 会话 ID
     * @param key 新的归档文件；全部事件都已过期、没有文件时为空
     * @param archivedSeq 覆盖到的最大 seq
     * @param archiveFrom 文件中最早的事件时间；没有文件时为空
     * @param now 当前时间
     */
    public void markArchived(String sessionId, String key, long archivedSeq, Instant archiveFrom, Instant now) {
        jdbc().sql("""
                        UPDATE rt.sessions
                           SET events_archive_key = :key, events_archived_seq = :archived_seq,
                               events_archive_from = :archive_from, events_archived_at = :now, updated_at = :now
                         WHERE id = :id
                        """)
                .param("key", key)
                .param("archived_seq", archivedSeq)
                .param("archive_from", archiveFrom == null ? null : JdbcValues.timestamptz(archiveFrom))
                .param("now", JdbcValues.timestamptz(now))
                .param("id", sessionId)
                .update();
    }

    // ---------------------------------------------------------------------------------------------
    // 事件保留
    // ---------------------------------------------------------------------------------------------

    /**
     * 有未删除会话的租户。
     *
     * @return 租户 ID
     */
    public List<String> tenantsWithSessions() {
        return jdbc().sql("SELECT DISTINCT tenant_id FROM rt.sessions WHERE deleted_at IS NULL ORDER BY tenant_id")
                .query(String.class)
                .list();
    }

    /**
     * 可能有事件早于截止时间的会话：创建早于截止时间，且从未清理过，或上次清理之后还有活动。
     *
     * @param tenantId 租户 ID
     * @param cutoff 截止时间
     * @param limit 至多条数
     * @return 会话 ID
     */
    public List<String> expiryCandidates(String tenantId, Instant cutoff, int limit) {
        return jdbc().sql("""
                        SELECT id FROM rt.sessions
                         WHERE tenant_id = :tenant_id AND deleted_at IS NULL AND created_at < :cutoff
                           AND (events_expired_before IS NULL
                                OR (events_expired_before < :cutoff AND last_activity_at >= events_expired_before))
                         ORDER BY id
                         LIMIT :limit
                        """)
                .param("tenant_id", tenantId)
                .param("cutoff", JdbcValues.timestamptz(cutoff))
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    /**
     * 锁住会话行，读取归档状态；被锁住时跳过。
     *
     * @param sessionId 会话 ID
     * @return 归档状态；行被锁住或已删除时为空
     */
    public Optional<ArchiveState> lockForExpiry(String sessionId) {
        return jdbc().sql("""
                        SELECT id, tenant_id, project_id, last_seq, events_archive_key,
                               COALESCE(events_archived_seq, 0) AS archived_seq, events_archive_from
                          FROM rt.sessions
                         WHERE id = :id AND deleted_at IS NULL
                           FOR UPDATE SKIP LOCKED
                        """)
                .param("id", sessionId)
                .query(SessionLifecycleRepository::archiveState)
                .optional();
    }

    /**
     * 删除数据库中早于截止时间的事件。
     *
     * @param sessionId 会话 ID
     * @param cutoff 截止时间
     * @return 删除的条数
     */
    public int deleteEventsBefore(String sessionId, Instant cutoff) {
        return jdbc().sql("DELETE FROM rt.session_events WHERE session_id = :session_id AND created_at < :cutoff")
                .param("session_id", sessionId)
                .param("cutoff", JdbcValues.timestamptz(cutoff))
                .update();
    }

    /**
     * 记录保留清理的结果：已执行到的截止时间与处理后的归档文件。
     *
     * @param sessionId 会话 ID
     * @param cutoff 截止时间
     * @param key 归档文件；被删除时为空
     * @param archiveFrom 文件中最早的事件时间；没有文件时为空
     */
    public void markExpired(String sessionId, Instant cutoff, String key, Instant archiveFrom) {
        jdbc().sql("""
                        UPDATE rt.sessions
                           SET events_expired_before = :cutoff, events_archive_key = :key,
                               events_archive_from = :archive_from
                         WHERE id = :id
                        """)
                .param("cutoff", JdbcValues.timestamptz(cutoff))
                .param("key", key)
                .param("archive_from", archiveFrom == null ? null : JdbcValues.timestamptz(archiveFrom))
                .param("id", sessionId)
                .update();
    }

    private static ArchiveState archiveState(ResultSet rs, int row) throws SQLException {
        return new ArchiveState(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("project_id"),
                rs.getLong("last_seq"),
                rs.getString("events_archive_key"),
                rs.getLong("archived_seq"),
                JdbcValues.instant(rs, "events_archive_from"));
    }

    /**
     * 一个已删除的会话。
     *
     * @param sessionId 会话 ID
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param deletedAt 删除时间
     */
    public record DeletedSession(String sessionId, String tenantId, String projectId, Instant deletedAt) {}

    /**
     * 会话的归档状态。
     *
     * @param sessionId 会话 ID
     * @param tenantId 租户 ID
     * @param projectId 项目 ID
     * @param lastSeq 已分配的最大 seq
     * @param archiveKey 归档文件；没有时为空
     * @param archivedSeq 归档覆盖到的最大 seq；从未归档时为 0
     * @param archiveFrom 文件中最早的事件时间；没有文件时为空
     */
    public record ArchiveState(
            String sessionId,
            String tenantId,
            String projectId,
            long lastSeq,
            String archiveKey,
            long archivedSeq,
            Instant archiveFrom) {}
}
