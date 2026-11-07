package cn.refinex.meridian.runtime.stream.infra.system;

import cn.refinex.meridian.engine.spi.event.EventType;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import cn.refinex.meridian.runtime.session.domain.SessionStatus;
import cn.refinex.meridian.runtime.stream.domain.StoredEvent;
import cn.refinex.meridian.runtime.stream.domain.StreamFilter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 会话事件的跨租户仓储：租约条件、seq 分配、事件插入与读取，以及状态事件的投影写入。方法都在调用方的事务中执行，
 * 事务边界由 {@code stream.app.SessionEventWriter} 定义。
 *
 * <p>锁顺序与认领、回收一致，先 {@code rt.turns} 再 {@code rt.sessions}，不会互相死锁。
 */
@Repository
@CrossTenant(reason = "事件写入由 Worker、API 与回收器共用，回收器没有请求上下文；租户与项目取自会话行，租约按轮次 ID 校验")
public class SessionEventRepository extends SystemRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public SessionEventRepository(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 校验并锁住轮次的租约：{@code FOR SHARE} 与回收器、置回队列的 {@code UPDATE} 冲突，所以本事务提交前租约不会被收回；
     * 回收器已提交时，重新检查的条件不再满足，返回 false。同一轮次的多个写入方之间互不阻塞。
     *
     * @param turnId 轮次 ID
     * @param nodeId 本节点 ID
     * @param epoch 期望的租约 epoch
     * @return 租约有效时为 true
     */
    public boolean lockLease(String turnId, String nodeId, long epoch) {
        return jdbc().sql("""
                        SELECT 1 FROM rt.turns
                         WHERE id = :turn_id AND status = 'running' AND lease_owner = :node_id AND lease_epoch = :epoch
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
     * 为一批事件分配连续的 seq：锁定会话行，{@code last_seq} 加上事件数，同时记录活动时间。
     *
     * @param sessionId 会话 ID
     * @param count 事件数，大于 0
     * @param now 当前时间
     * @return 分配结果；会话不存在时为空
     */
    public Optional<SeqAllocation> allocate(String sessionId, int count, Instant now) {
        return jdbc().sql("""
                        UPDATE rt.sessions SET last_seq = last_seq + :count, last_activity_at = :now
                         WHERE id = :session_id
                        RETURNING last_seq, tenant_id, project_id
                        """)
                .param("count", count)
                .param("now", JdbcValues.timestamptz(now))
                .param("session_id", sessionId)
                .query((rs, row) -> new SeqAllocation(
                        rs.getLong("last_seq") - count + 1, rs.getString("tenant_id"), rs.getString("project_id")))
                .optional();
    }

    /**
     * 插入一个事件。
     *
     * @param event 事件行
     */
    public void insert(StoredEvent event) {
        jdbc().sql("""
                        INSERT INTO rt.session_events (session_id, seq, id, tenant_id, project_id, thread_id, turn_id,
                                                       type, data, created_at)
                        VALUES (:session_id, :seq, :id, :tenant_id, :project_id, :thread_id, :turn_id,
                                :type, :data, :created_at)
                        """)
                .param("session_id", event.sessionId())
                .param("seq", event.seq())
                .param("id", event.id())
                .param("tenant_id", event.tenantId())
                .param("project_id", event.projectId())
                .param("thread_id", event.threadId())
                .param("turn_id", event.turnId())
                .param("type", event.type().value())
                .param("data", JdbcValues.jsonb(event.data()))
                .param("created_at", JdbcValues.timestamptz(event.createdAt()))
                .update();
    }

    /**
     * 更新会话状态；进入终止状态时同时记录终止时间。
     *
     * @param sessionId 会话 ID
     * @param status 新状态
     * @param now 当前时间
     */
    public void updateSessionStatus(String sessionId, SessionStatus status, Instant now) {
        jdbc().sql("""
                        UPDATE rt.sessions
                           SET status = :status, updated_at = :now,
                               terminated_at = CASE WHEN :status = 'terminated' THEN :now ELSE terminated_at END
                         WHERE id = :session_id
                        """)
                .param("status", status.value())
                .param("now", JdbcValues.timestamptz(now))
                .param("session_id", sessionId)
                .update();
    }

    /**
     * 更新线程状态。线程必须属于该会话，避免事件内容中的线程 ID 改到别的会话。
     *
     * @param sessionId 会话 ID
     * @param threadId 线程 ID
     * @param status 新状态
     * @param now 当前时间
     */
    public void updateThreadStatus(String sessionId, String threadId, SessionStatus status, Instant now) {
        jdbc().sql("""
                        UPDATE rt.threads SET status = :status, updated_at = :now
                         WHERE id = :thread_id AND session_id = :session_id
                        """)
                .param("status", status.value())
                .param("now", JdbcValues.timestamptz(now))
                .param("thread_id", threadId)
                .param("session_id", sessionId)
                .update();
    }

    /**
     * 按 seq 顺序读取 {@code afterSeq} 之后的事件。
     *
     * @param sessionId 会话 ID
     * @param afterSeq 起点（不含），从头读取时为 0
     * @param limit 最多返回的条数
     * @return 事件，按 seq 升序
     */
    public List<StoredEvent> listAfter(String sessionId, long afterSeq, int limit) {
        return jdbc().sql("""
                        SELECT session_id, seq, id, tenant_id, project_id, thread_id, turn_id, type, data, created_at
                          FROM rt.session_events
                         WHERE session_id = :session_id AND seq > :after_seq
                         ORDER BY seq
                         LIMIT :limit
                        """)
                .param("session_id", sessionId)
                .param("after_seq", afterSeq)
                .param("limit", limit)
                .query(SessionEventRepository::map)
                .list();
    }

    /**
     * 按 seq 顺序读取 {@code (afterSeq, uptoSeq]} 区间内、在订阅范围内的事件，SSE 补发与对账使用（架构设计「SSE 协议」）。
     * {@code session.status_terminated} 总在结果中，即使它不在范围内：连接据此在会话终止后关闭。
     *
     * @param sessionId 会话 ID
     * @param afterSeq 起点（不含）
     * @param uptoSeq 终点（含）
     * @param filter 订阅范围
     * @param limit 最多返回的条数
     * @return 事件，按 seq 升序
     */
    public List<StoredEvent> listRange(String sessionId, long afterSeq, long uptoSeq, StreamFilter filter, int limit) {
        String mode;
        String threadId;
        switch (filter) {
            case StreamFilter.All _ -> {
                mode = "all";
                threadId = null;
            }
            case StreamFilter.Main main -> {
                mode = "main";
                threadId = main.mainThreadId();
            }
            case StreamFilter.Thread thread -> {
                mode = "thread";
                threadId = thread.threadId();
            }
        }
        return jdbc().sql("""
                        SELECT session_id, seq, id, tenant_id, project_id, thread_id, turn_id, type, data, created_at
                          FROM rt.session_events
                         WHERE session_id = :session_id AND seq > :after_seq AND seq <= :upto_seq
                           AND (:mode = 'all'
                                OR type = 'session.status_terminated'
                                OR (:mode = 'main' AND (thread_id IS NULL OR thread_id = :thread_id
                                                        OR type = ANY (:thread_status_types)))
                                OR (:mode = 'thread' AND thread_id = :thread_id))
                         ORDER BY seq
                         LIMIT :limit
                        """)
                .param("session_id", sessionId)
                .param("after_seq", afterSeq)
                .param("upto_seq", uptoSeq)
                .param("mode", mode)
                .param("thread_id", threadId)
                .param(
                        "thread_status_types",
                        JdbcValues.textArray(StreamFilter.THREAD_STATUS_EVENTS.stream()
                                .map(EventType::value)
                                .toList()))
                .param("limit", limit)
                .query(SessionEventRepository::map)
                .list();
    }

    /**
     * 读取会话已分配的最大 seq 与当前状态，SSE 对账使用。
     *
     * @param sessionId 会话 ID
     * @return 最大 seq 与状态；会话不存在时为空
     */
    public Optional<SessionProgress> progress(String sessionId) {
        return jdbc().sql("SELECT last_seq, status FROM rt.sessions WHERE id = :session_id")
                .param("session_id", sessionId)
                .query((rs, row) ->
                        new SessionProgress(rs.getLong("last_seq"), SessionStatus.of(rs.getString("status"))))
                .optional();
    }

    /**
     * 读取一轮写入的指定类型的事件，按 seq 升序。
     *
     * @param sessionId 会话 ID
     * @param turnId 轮次 ID
     * @param types 事件类型
     * @return 事件
     */
    public List<StoredEvent> listByTurn(String sessionId, String turnId, List<EventType> types) {
        return jdbc().sql("""
                        SELECT session_id, seq, id, tenant_id, project_id, thread_id, turn_id, type, data, created_at
                          FROM rt.session_events
                         WHERE session_id = :session_id AND turn_id = :turn_id AND type = ANY (:types)
                         ORDER BY seq
                        """)
                .param("session_id", sessionId)
                .param("turn_id", turnId)
                .param(
                        "types",
                        JdbcValues.textArray(
                                types.stream().map(EventType::value).toList()))
                .query(SessionEventRepository::map)
                .list();
    }

    private static StoredEvent map(ResultSet rs, int row) throws SQLException {
        return new StoredEvent(
                rs.getString("session_id"),
                rs.getLong("seq"),
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("project_id"),
                rs.getString("thread_id"),
                rs.getString("turn_id"),
                EventType.fromValue(rs.getString("type")),
                rs.getString("data"),
                JdbcValues.instant(rs, "created_at"));
    }

    /**
     * 会话的事件进度。
     *
     * @param lastSeq 已分配的最大 seq，没有事件时为 0
     * @param status 会话状态
     */
    public record SessionProgress(long lastSeq, SessionStatus status) {}

    /**
     * 一次 seq 分配的结果。
     *
     * @param firstSeq 本批第一个事件的 seq
     * @param tenantId 会话的租户
     * @param projectId 会话的项目
     */
    public record SeqAllocation(long firstSeq, String tenantId, String projectId) {}
}
