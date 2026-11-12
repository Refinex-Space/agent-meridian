package cn.refinex.meridian.runtime.webhook.infra;

import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import cn.refinex.meridian.runtime.webhook.domain.DeliveryFilter;
import cn.refinex.meridian.runtime.webhook.domain.DeliveryStatus;
import cn.refinex.meridian.runtime.webhook.domain.WebhookDelivery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** 投递记录的项目级仓储（公开 API，ADR-0049）：租户与项目取自 {@code PlatformContext}。 */
@Repository
public class WebhookDeliveryRepository extends TenantScopedRepository {

    private static final String COLUMNS = """
            id, webhook_id, session_id, event_id, event_type, status, attempts, next_attempt_at, last_attempt_at,
            last_response_status, last_error, redelivery_of, created_at, completed_at
            """;

    /**
     * 创建仓储。
     *
     * @param dataSource 应用数据源
     */
    public WebhookDeliveryRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 按 ID 降序（新的在前）分页列出。
     *
     * @param page 分页参数
     * @param filter 过滤条件
     * @return 最多 {@code page.fetchSize()} 条
     */
    public List<WebhookDelivery> list(PageRequest page, DeliveryFilter filter) {
        return sql("SELECT " + COLUMNS + """
                          FROM rt.webhook_deliveries
                         WHERE tenant_id = :tenant_id AND project_id = :project_id
                           AND (CAST(:after AS text) IS NULL OR id < :after)
                           AND (CAST(:webhook_id AS text) IS NULL OR webhook_id = :webhook_id)
                           AND (CAST(:session_id AS text) IS NULL OR session_id = :session_id)
                           AND (CAST(:status AS text) IS NULL OR status = :status)
                         ORDER BY id DESC
                         LIMIT :limit
                        """)
                .param("after", page.after() == null ? null : page.after().key(0))
                .param("webhook_id", filter.webhookId())
                .param("session_id", filter.sessionId())
                .param(
                        "status",
                        filter.status() == null ? null : filter.status().value())
                .param("limit", page.fetchSize())
                .query(WebhookDeliveryRepository::map)
                .list();
    }

    /**
     * 读取一条。
     *
     * @param id 投递记录 ID
     * @return 记录；不存在或不属于当前项目时为空
     */
    public Optional<WebhookDelivery> find(String id) {
        return sql("SELECT " + COLUMNS + """
                          FROM rt.webhook_deliveries
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id
                        """)
                .param("id", id)
                .query(WebhookDeliveryRepository::map)
                .optional();
    }

    /**
     * 复制一条记录为新的待投递记录（手动重投）：同一 Webhook、同一事件与请求体，次数清零，立即到期。
     *
     * @param originalId 原记录 ID
     * @param newId 新记录 ID
     * @param now 当前时间
     * @param deadline 新记录的截止时间
     * @return 复制成功时为 true；原记录不存在或不属于当前项目时为 false
     */
    public boolean copyForRedelivery(String originalId, String newId, Instant now, Instant deadline) {
        return sql("""
                        INSERT INTO rt.webhook_deliveries (id, tenant_id, project_id, webhook_id, session_id, event_id,
                                                           event_type, payload, status, next_attempt_at, deadline_at,
                                                           redelivery_of, created_at)
                        SELECT :new_id, tenant_id, project_id, webhook_id, session_id, event_id, event_type, payload,
                               'pending', :now, :deadline, id, :now
                          FROM rt.webhook_deliveries
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :original_id
                        """)
                        .param("new_id", newId)
                        .param("now", now)
                        .param("deadline", deadline)
                        .param("original_id", originalId)
                        .update()
                == 1;
    }

    private static WebhookDelivery map(ResultSet rs, int row) throws SQLException {
        int responseStatus = rs.getInt("last_response_status");
        Integer lastResponseStatus = rs.wasNull() ? null : responseStatus;
        return new WebhookDelivery(
                rs.getString("id"),
                rs.getString("webhook_id"),
                rs.getString("session_id"),
                rs.getString("event_id"),
                rs.getString("event_type"),
                DeliveryStatus.of(rs.getString("status")),
                rs.getInt("attempts"),
                JdbcValues.instant(rs, "next_attempt_at"),
                JdbcValues.instant(rs, "last_attempt_at"),
                lastResponseStatus,
                rs.getString("last_error"),
                rs.getString("redelivery_of"),
                JdbcValues.instant(rs, "created_at"),
                JdbcValues.instant(rs, "completed_at"));
    }
}
