package cn.refinex.meridian.runtime.modelrouting.infra.system;

import cn.refinex.meridian.engine.spi.event.TokenUsage;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import cn.refinex.meridian.runtime.modelrouting.domain.ModelUsageMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 每轮每个模型的用量累计（{@code rt.turn_usage}，ADR-0050）：结算时与 outbox 的 {@code usage} 消息同一事务累加，轮次结束时读出本轮与会话
 * 的合计写成 {@code session.usage}。
 */
@Repository
@CrossTenant(reason = "结算与轮次收尾在 Worker 路径上执行，按轮次与会话 ID 定位，租户与项目随用量消息写入")
public class TurnUsageRepository extends SystemRepository {

    private static final String SUMS = """
            COALESCE(SUM(input_tokens), 0) AS input_tokens, COALESCE(SUM(output_tokens), 0) AS output_tokens,
            COALESCE(SUM(cache_read_tokens), 0) AS cache_read_tokens,
            COALESCE(SUM(cache_write_tokens), 0) AS cache_write_tokens,
            COALESCE(SUM(reasoning_tokens), 0) AS reasoning_tokens
            """;

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public TurnUsageRepository(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 累加一条用量记录。
     *
     * @param message 用量记录，带会话与轮次
     * @param now 当前时间
     */
    public void add(ModelUsageMessage message, Instant now) {
        jdbc().sql("""
                        INSERT INTO rt.turn_usage (turn_id, model_id, tenant_id, project_id, session_id, calls, input_tokens,
                                                   output_tokens, cache_read_tokens, cache_write_tokens, reasoning_tokens,
                                                   updated_at)
                        VALUES (:turn_id, :model_id, :tenant_id, :project_id, :session_id, 1, :input, :output, :cache_read,
                                :cache_write, :reasoning, :now)
                        ON CONFLICT (turn_id, model_id) DO UPDATE
                           SET calls = turn_usage.calls + 1,
                               input_tokens = turn_usage.input_tokens + EXCLUDED.input_tokens,
                               output_tokens = turn_usage.output_tokens + EXCLUDED.output_tokens,
                               cache_read_tokens = turn_usage.cache_read_tokens + EXCLUDED.cache_read_tokens,
                               cache_write_tokens = turn_usage.cache_write_tokens + EXCLUDED.cache_write_tokens,
                               reasoning_tokens = turn_usage.reasoning_tokens + EXCLUDED.reasoning_tokens,
                               updated_at = EXCLUDED.updated_at
                        """)
                .param("turn_id", message.turnId())
                .param("model_id", message.modelId())
                .param("tenant_id", message.tenantId())
                .param("project_id", message.projectId())
                .param("session_id", message.sessionId())
                .param("input", message.inputTokens())
                .param("output", message.outputTokens())
                .param("cache_read", message.cacheReadTokens())
                .param("cache_write", message.cacheWriteTokens())
                .param("reasoning", message.reasoningTokens())
                .param("now", JdbcValues.timestamptz(now))
                .update();
    }

    /**
     * 一轮按模型的用量，按模型 ID 排序。
     *
     * @param turnId 轮次 ID
     * @return 用量；没有调用时为空列表
     */
    public List<ModelTurnUsage> byModel(String turnId) {
        return jdbc().sql("""
                        SELECT model_id, calls, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens,
                               reasoning_tokens
                          FROM rt.turn_usage
                         WHERE turn_id = :turn_id
                         ORDER BY model_id
                        """)
                .param("turn_id", turnId)
                .query((rs, row) -> new ModelTurnUsage(rs.getString("model_id"), rs.getInt("calls"), usage(rs)))
                .list();
    }

    /**
     * 会话的用量累计。
     *
     * @param sessionId 会话 ID
     * @return 全部轮次的合计
     */
    public TokenUsage session(String sessionId) {
        return jdbc().sql("SELECT " + SUMS + " FROM rt.turn_usage WHERE session_id = :session_id")
                .param("session_id", sessionId)
                .query((rs, row) -> usage(rs))
                .single();
    }

    /**
     * 删除会话的全部记录（会话清理）。
     *
     * @param sessionId 会话 ID
     * @return 删除的行数
     */
    public int deleteSession(String sessionId) {
        return jdbc().sql("DELETE FROM rt.turn_usage WHERE session_id = :session_id")
                .param("session_id", sessionId)
                .update();
    }

    private static TokenUsage usage(ResultSet rs) throws SQLException {
        return new TokenUsage(
                Math.toIntExact(rs.getLong("input_tokens")),
                Math.toIntExact(rs.getLong("output_tokens")),
                Math.toIntExact(rs.getLong("cache_read_tokens")),
                Math.toIntExact(rs.getLong("cache_write_tokens")),
                Math.toIntExact(rs.getLong("reasoning_tokens")));
    }

    /**
     * 一个模型在一轮中的用量。
     *
     * @param modelId 模型 ID
     * @param calls 结算的调用次数
     * @param usage 用量
     */
    public record ModelTurnUsage(String modelId, int calls, TokenUsage usage) {}
}
