package cn.refinex.meridian.runtime.approval.infra;

import cn.refinex.meridian.engine.spi.event.PendingAction;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 待处理动作 {@code rt.pending_actions} 的项目级仓储（架构设计「待处理动作」「核心表」）。方法在调用方的事务中执行。
 *
 * <p>{@code payload} 保存相关的工具调用（{@code tool_use_id}、{@code tool_name}、{@code input}）与按动作类型附加的字段，
 * 截止时间单独成列，超时扫描使用。
 */
@Repository
public class PendingActionRepository extends TenantScopedRepository {

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
}
