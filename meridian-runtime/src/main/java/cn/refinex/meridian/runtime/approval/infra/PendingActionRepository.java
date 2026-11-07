package cn.refinex.meridian.runtime.approval.infra;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.engine.spi.event.PendingAction;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import cn.refinex.meridian.runtime.approval.domain.PendingActionStatus;
import cn.refinex.meridian.runtime.approval.domain.StoredPendingAction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
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
                JdbcValues.instant(rs, "resolved_at"));
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
