package cn.refinex.meridian.runtime.approval.infra;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.engine.spi.engine.RecoveryOrigin;
import cn.refinex.meridian.engine.spi.engine.input.RecoveryNote;
import cn.refinex.meridian.engine.spi.event.PendingAction;
import cn.refinex.meridian.engine.spi.event.UserEvent.RecoveryDecision;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import cn.refinex.meridian.runtime.approval.domain.ActionConclusion;
import cn.refinex.meridian.runtime.approval.domain.PendingActionFilter;
import cn.refinex.meridian.runtime.approval.domain.PendingActionStatus;
import cn.refinex.meridian.runtime.approval.domain.PendingActionView;
import cn.refinex.meridian.runtime.approval.domain.ResolvableAction;
import cn.refinex.meridian.runtime.approval.domain.StoredPendingAction;
import cn.refinex.meridian.runtime.stream.infra.EngineJson;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

/**
 * 待处理动作 {@code rt.pending_actions} 的项目级仓储（架构设计「待处理动作」「核心表」）。方法在调用方的事务中执行。
 *
 * <p>{@code payload} 保存相关的工具调用（{@code tool_use_id}、{@code tool_name}、{@code input}）与按动作类型附加的字段，
 * 截止时间单独成列，超时扫描使用。
 */
@Repository
public class PendingActionRepository extends TenantScopedRepository {

    private static final String COLUMNS = """
            id, session_id, thread_id, turn_id, type, status, payload::text AS payload, expires_at, created_at,
            resolved_at
            """;

    /** 与会话连接查询时的列：动作的列加回应者、结论与会话的智能体。 */
    private static final String VIEW_COLUMNS = """
            a.id, a.session_id, a.thread_id, a.turn_id, a.type, a.status, a.payload::text AS payload, a.expires_at,
            a.created_at, a.resolved_at, a.decided_by, a.response::text AS response, s.agent_id
            """;

    private static final TypeReference<Map<String, Object>> PAYLOAD = new TypeReference<>() {};

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public PendingActionRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 写入一个等待回应的动作。
     *
     * @param sessionId 会话 ID
     * @param turnId 产生动作的轮次
     * @param threadId 产生动作的线程
     * @param action 动作，必须已设置截止时间
     * @param extra 按动作类型附加到 {@code payload} 的字段；可为空映射
     * @param now 当前时间
     * @throws IllegalArgumentException 动作没有截止时间时
     */
    public void insert(
            String sessionId,
            String turnId,
            String threadId,
            PendingAction action,
            Map<String, Object> extra,
            Instant now) {
        if (action.expiresAt() == null) {
            throw new IllegalArgumentException("待处理动作必须有截止时间：" + action.id());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_use_id", action.toolUseId());
        payload.put("tool_name", action.toolName());
        payload.put("input", action.input());
        if (!action.suggestedRules().isEmpty()) {
            payload.put("suggested_rules", action.suggestedRules());
        }
        payload.putAll(extra);
        sql("""
                        INSERT INTO rt.pending_actions (id, tenant_id, project_id, session_id, turn_id, thread_id, type,
                                                        payload, status, expires_at, created_at)
                        VALUES (:id, :tenant_id, :project_id, :session_id, :turn_id, :thread_id, :type, :payload,
                                'pending', :expires_at, :now)
                        """)
                .param("id", action.id())
                .param("session_id", sessionId)
                .param("turn_id", turnId)
                .param("thread_id", threadId)
                .param("type", action.type().value())
                .param("payload", JdbcValues.jsonbOf(payload))
                .param("expires_at", action.expiresAt())
                .param("now", now)
                .update();
    }

    /**
     * 按创建顺序分页列出会话中某个状态的动作（ID 为 ULID，按时间有序）。
     *
     * @param sessionId 会话 ID
     * @param status 状态
     * @param page 分页参数；游标的唯一键是上一页最后一个动作的 ID
     * @return 最多 {@code page.fetchSize()} 个动作
     */
    public List<StoredPendingAction> page(String sessionId, PendingActionStatus status, PageRequest page) {
        return sql("SELECT " + COLUMNS + """
                          FROM rt.pending_actions
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND session_id = :session_id
                           AND status = :status AND (CAST(:after_id AS text) IS NULL OR id > :after_id)
                         ORDER BY id
                         LIMIT :limit
                        """)
                .param("session_id", sessionId)
                .param("status", status.value())
                .param("after_id", page.after() == null ? null : page.after().key(0))
                .param("limit", page.fetchSize())
                .query(PendingActionRepository::map)
                .list();
    }

    /**
     * 按创建顺序分页列出会话中某个状态的动作，带会话的智能体、回应者与结论（{@code GET /v1/sessions/{id}/pending-actions}）。
     *
     * @param sessionId 会话 ID
     * @param status 状态
     * @param page 分页参数；游标的唯一键是上一页最后一个动作的 ID
     * @return 最多 {@code page.fetchSize()} 个动作
     */
    public List<PendingActionView> sessionViews(String sessionId, PendingActionStatus status, PageRequest page) {
        return sql("SELECT " + VIEW_COLUMNS + """
                          FROM rt.pending_actions a
                          JOIN rt.sessions s ON s.id = a.session_id
                         WHERE a.tenant_id = :tenant_id AND a.project_id = :project_id AND a.session_id = :session_id
                           AND a.status = :status AND (CAST(:after_id AS text) IS NULL OR a.id > :after_id)
                         ORDER BY a.id
                         LIMIT :limit
                        """)
                .param("session_id", sessionId)
                .param("status", status.value())
                .param("after_id", page.after() == null ? null : page.after().key(0))
                .param("limit", page.fetchSize())
                .query(PendingActionRepository::mapView)
                .list();
    }

    /**
     * 分页列出当前项目全部未删除会话的动作，新创建的在前（{@code GET /v1/pending-actions}），走 {@code pending_actions_project_idx}
     * 或只含等待回应的 {@code pending_actions_project_pending_idx}。
     *
     * @param filter 筛选条件
     * @param page 分页参数；游标的唯一键是上一页最后一个动作的 ID
     * @return 最多 {@code page.fetchSize()} 个动作
     */
    public List<PendingActionView> projectViews(PendingActionFilter filter, PageRequest page) {
        return sql("SELECT " + VIEW_COLUMNS + """
                          FROM rt.pending_actions a
                          JOIN rt.sessions s ON s.id = a.session_id
                         WHERE a.tenant_id = :tenant_id AND a.project_id = :project_id AND s.deleted_at IS NULL
                           AND (CAST(:status AS text) IS NULL OR a.status = :status)
                           AND (CAST(:type AS text) IS NULL OR a.type = :type)
                           AND (CAST(:agent_filter AS text) IS NULL OR s.agent_id = :agent_filter)
                           AND (CAST(:session_filter AS text) IS NULL OR a.session_id = :session_filter)
                           AND (CAST(:after_id AS text) IS NULL OR a.id < :after_id)
                         ORDER BY a.id DESC
                         LIMIT :limit
                        """)
                .param(
                        "status",
                        filter.status() == null ? null : filter.status().value())
                .param("type", filter.type() == null ? null : filter.type().value())
                .param("agent_filter", filter.agentId())
                .param("session_filter", filter.sessionId())
                .param("after_id", page.after() == null ? null : page.after().key(0))
                .param("limit", page.fetchSize())
                .query(PendingActionRepository::mapView)
                .list();
    }

    /**
     * 锁定并读取会话中的指定动作，供回应事件的校验使用；锁到事务结束，同一动作的并发回应因此串行。
     *
     * @param sessionId 会话 ID
     * @param actionIds 动作 ID
     * @return 存在于该会话中的动作；不存在的 ID 不在结果中
     */
    public List<StoredPendingAction> lockAll(String sessionId, Collection<String> actionIds) {
        if (actionIds.isEmpty()) {
            return List.of();
        }
        return sql("SELECT " + COLUMNS + """
                          FROM rt.pending_actions
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND session_id = :session_id
                           AND id = ANY (:ids)
                           FOR UPDATE
                        """)
                .param("session_id", sessionId)
                .param("ids", JdbcValues.textArray(List.copyOf(actionIds)))
                .query(PendingActionRepository::map)
                .list();
    }

    /**
     * 作废会话中全部等待回应的动作：会话归档或删除时调用（架构设计「状态机」）。
     *
     * @param sessionId 会话 ID
     * @param now 当前时间
     * @return 作废的动作数
     */
    public int expirePending(String sessionId, Instant now) {
        return sql("""
                        UPDATE rt.pending_actions SET status = 'expired', resolved_at = :now
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND session_id = :session_id
                           AND status = 'pending'
                        """).param("now", now).param("session_id", sessionId).update();
    }

    /**
     * 写入一个已有结论的动作：无人值守会话自动拒绝的确认类调用，或已经回应过、由平台直接交出的自定义工具结果（架构设计
     * 「待处理动作」）。
     *
     * @param sessionId 会话 ID
     * @param turnId 产生动作的轮次
     * @param threadId 产生动作的线程
     * @param action 动作，必须已设置截止时间
     * @param extra 按动作类型附加到 {@code payload} 的字段；可为空映射
     * @param conclusion 结论
     * @param decidedBy 做出决定的主体；可为空
     * @param now 当前时间
     */
    public void insertConcluded(
            String sessionId,
            String turnId,
            String threadId,
            PendingAction action,
            Map<String, Object> extra,
            ActionConclusion conclusion,
            String decidedBy,
            Instant now) {
        insert(sessionId, turnId, threadId, action, extra, now);
        conclude(action.id(), PendingActionStatus.RESOLVED, conclusion, decidedBy, now);
    }

    /**
     * 锁定并读取一轮的全部动作及其结论，按创建顺序（ID 为 ULID）。生成恢复轮时调用，同一轮的并发回应因此串行。
     *
     * @param turnId 产生动作的轮次
     * @return 动作
     */
    public List<ResolvableAction> lockByTurn(String turnId) {
        return sql("SELECT " + COLUMNS + ", " + """
                               response::text AS response, consumed_turn_id
                          FROM rt.pending_actions
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND turn_id = :turn_id
                         ORDER BY id
                           FOR UPDATE
                        """)
                .param("turn_id", turnId)
                .query(PendingActionRepository::mapResolvable)
                .list();
    }

    /**
     * 读取线程中已回应、尚未交出的自定义工具结论（按工具调用 ID），确认轮再次停在 {@code TOOL_SUSPENDED} 时由平台直接交出。
     *
     * @param threadId 线程 ID
     * @param toolUseIds 工具调用 ID
     * @return 工具调用 ID → 结论
     */
    public Map<String, ActionConclusion> findUnconsumedCustomResults(String threadId, Collection<String> toolUseIds) {
        if (toolUseIds.isEmpty()) {
            return Map.of();
        }
        Map<String, ActionConclusion> found = new LinkedHashMap<>();
        sql("""
                        SELECT payload ->> 'tool_use_id' AS tool_use_id, response::text AS response
                          FROM rt.pending_actions
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND thread_id = :thread_id
                           AND type = 'custom_tool_result' AND consumed_turn_id IS NULL AND response IS NOT NULL
                           AND payload ->> 'tool_use_id' = ANY (:tool_use_ids)
                         ORDER BY id
                        """)
                .param("thread_id", threadId)
                .param("tool_use_ids", JdbcValues.textArray(List.copyOf(toolUseIds)))
                .query((rs, row) -> Map.entry(
                        rs.getString("tool_use_id"), EngineJson.read(rs.getString("response"), ActionConclusion.class)))
                .list()
                .forEach(entry -> found.putIfAbsent(entry.getKey(), entry.getValue()));
        return found;
    }

    /**
     * 记录一个仍在等待的动作的结论：调用方的回应（{@code resolved}）、超时（{@code expired}）、中断或自动拒绝（{@code resolved}）。
     *
     * @param actionId 动作 ID
     * @param status 新状态，{@code resolved} 或 {@code expired}
     * @param conclusion 结论
     * @param decidedBy 做出决定的主体；平台生成的结论为空
     * @param now 当前时间
     * @return 动作仍在等待时为 true
     */
    public boolean conclude(
            String actionId, PendingActionStatus status, ActionConclusion conclusion, String decidedBy, Instant now) {
        return sql("""
                                UPDATE rt.pending_actions
                                   SET status = :status, response = :response, decided_by = :decided_by,
                                       remember_scope = :remember_scope, resolved_at = :now
                                 WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id
                                   AND status = 'pending'
                                """)
                        .param("status", status.value())
                        .param("response", JdbcValues.jsonb(EngineJson.write(conclusion)))
                        .param("decided_by", decidedBy)
                        .param(
                                "remember_scope",
                                conclusion.remember() == null
                                        ? null
                                        : conclusion.remember().value())
                        .param("now", now)
                        .param("id", actionId)
                        .update()
                == 1;
    }

    /**
     * 记录动作的结论已交给哪一个恢复轮。
     *
     * @param actionIds 动作 ID
     * @param turnId 恢复轮 ID
     */
    public void markConsumed(Collection<String> actionIds, String turnId) {
        if (actionIds.isEmpty()) {
            return;
        }
        sql("""
                        UPDATE rt.pending_actions SET consumed_turn_id = :turn_id
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = ANY (:ids)
                        """)
                .param("turn_id", turnId)
                .param("ids", JdbcValues.textArray(List.copyOf(actionIds)))
                .update();
    }

    /**
     * 读取被某个恢复轮消费、选择了重试的恢复确认，轮次开始时平台据此用原幂等键重新执行（架构设计「待处理动作」）。
     *
     * @param turnId 恢复轮 ID
     * @return 动作，按创建顺序
     */
    public List<ResolvableAction> listRetriesConsumedBy(String turnId) {
        return sql("SELECT " + COLUMNS + ", " + """
                               response::text AS response, consumed_turn_id
                          FROM rt.pending_actions
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND consumed_turn_id = :turn_id
                           AND type = 'recovery_confirmation'
                         ORDER BY id
                        """)
                .param("turn_id", turnId)
                .query(PendingActionRepository::mapResolvable)
                .list()
                .stream()
                .filter(a -> a.conclusion() != null && a.conclusion().decision() == RecoveryDecision.RETRY)
                .toList();
    }

    private static ResolvableAction mapResolvable(ResultSet rs, int row) throws SQLException {
        StoredPendingAction action = map(rs, row);
        Map<String, Object> payload = JsonMappers.lenient().readValue(rs.getString("payload"), PAYLOAD);
        String response = rs.getString("response");
        Object note = payload.get("recovery_note");
        Object mode = payload.get("recovery_mode");
        return new ResolvableAction(
                action,
                response == null ? null : EngineJson.read(response, ActionConclusion.class),
                rs.getString("consumed_turn_id"),
                (String) payload.get("idempotency_key"),
                note == null ? null : EngineJson.convert(note, RecoveryNote.class),
                mode == null ? null : RecoveryOrigin.Mode.valueOf(((String) mode).toUpperCase(Locale.ROOT)));
    }

    private static PendingActionView mapView(ResultSet rs, int row) throws SQLException {
        String response = rs.getString("response");
        return new PendingActionView(
                map(rs, row),
                rs.getString("agent_id"),
                rs.getString("decided_by"),
                response == null ? null : EngineJson.read(response, ActionConclusion.class));
    }

    private static StoredPendingAction map(ResultSet rs, int row) throws SQLException {
        Map<String, Object> payload = JsonMappers.lenient().readValue(rs.getString("payload"), PAYLOAD);
        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) payload.get("input");
        return new StoredPendingAction(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("thread_id"),
                rs.getString("turn_id"),
                typeOf(rs.getString("type")),
                PendingActionStatus.of(rs.getString("status")),
                (String) payload.get("tool_use_id"),
                (String) payload.get("tool_name"),
                input,
                JdbcValues.instant(rs, "expires_at"),
                JdbcValues.instant(rs, "created_at"),
                JdbcValues.instant(rs, "resolved_at"),
                suggestedRules(payload.get("suggested_rules")));
    }

    /** {@code payload.suggested_rules}（D21）；T19b 之前的动作没有。 */
    private static List<String> suggestedRules(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    private static PendingAction.Type typeOf(String value) {
        for (PendingAction.Type type : PendingAction.Type.values()) {
            if (type.value().equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的待处理动作类型：" + value);
    }
}
