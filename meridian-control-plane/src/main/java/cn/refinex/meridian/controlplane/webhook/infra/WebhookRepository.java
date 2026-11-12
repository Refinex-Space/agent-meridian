package cn.refinex.meridian.controlplane.webhook.infra;

import cn.refinex.meridian.common.page.PageRequest;
import cn.refinex.meridian.controlplane.webhook.domain.Webhook;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** {@code cp.webhooks} 的项目级仓储（ADR-0049）。租户与项目取自 {@code PlatformContext}。 */
@Repository
public class WebhookRepository extends TenantScopedRepository {

    private static final String COLUMNS = """
            id, tenant_id, project_id, url, events, description, enabled, created_by, created_at, updated_at
            """;

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public WebhookRepository(DataSource dataSource) {
        super(dataSource, TenantScope.PROJECT);
    }

    /**
     * 插入 Webhook。
     *
     * @param webhook Webhook，租户与项目必须等于上下文中的
     * @param secretCiphertext 签名密钥的密文
     */
    public void insert(Webhook webhook, byte[] secretCiphertext) {
        sql("""
                        INSERT INTO cp.webhooks (id, tenant_id, project_id, url, events, description, enabled,
                                                 secret_ciphertext, created_by, created_at, updated_at)
                        VALUES (:id, :tenant_id, :project_id, :url, :events, :description, :enabled,
                                :secret, :created_by, :created_at, :updated_at)
                        """)
                .param("id", webhook.id())
                .param("url", webhook.url())
                .param("events", JdbcValues.textArray(webhook.events()))
                .param("description", webhook.description())
                .param("enabled", webhook.enabled())
                .param("secret", secretCiphertext)
                .param("created_by", webhook.createdBy())
                .param("created_at", webhook.createdAt())
                .param("updated_at", webhook.updatedAt())
                .update();
    }

    /**
     * 替换可改的字段：地址、事件、说明、是否启用。
     *
     * @param webhook 修改后的 Webhook
     */
    public void update(Webhook webhook) {
        sql("""
                        UPDATE cp.webhooks
                           SET url = :url, events = :events, description = :description, enabled = :enabled,
                               updated_at = :updated_at
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id
                        """)
                .param("id", webhook.id())
                .param("url", webhook.url())
                .param("events", JdbcValues.textArray(webhook.events()))
                .param("description", webhook.description())
                .param("enabled", webhook.enabled())
                .param("updated_at", webhook.updatedAt())
                .update();
    }

    /**
     * 读取 Webhook。
     *
     * @param id Webhook ID
     * @param forUpdate 是否加行锁
     * @return Webhook；不存在时为空
     */
    public Optional<Webhook> find(String id, boolean forUpdate) {
        return sql("SELECT " + COLUMNS + """
                          FROM cp.webhooks
                         WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id
                        """ + (forUpdate ? " FOR UPDATE" : ""))
                .param("id", id)
                .query(WebhookRepository::map)
                .optional();
    }

    /**
     * 按 ID 升序分页列出。
     *
     * @param page 分页参数
     * @return 最多 {@code page.fetchSize()} 个
     */
    public List<Webhook> list(PageRequest page) {
        return sql("SELECT " + COLUMNS + """
                          FROM cp.webhooks
                         WHERE tenant_id = :tenant_id AND project_id = :project_id
                           AND (CAST(:after AS text) IS NULL OR id > :after)
                         ORDER BY id LIMIT :limit
                        """)
                .param("after", page.after() == null ? null : page.after().key(0))
                .param("limit", page.fetchSize())
                .query(WebhookRepository::map)
                .list();
    }

    /**
     * 列出项目的全部 Webhook 与签名密钥密文，按 ID 升序（内部接口）。
     *
     * @return Webhook 与密文
     */
    public List<WithSecret> listWithSecrets() {
        return sql("SELECT " + COLUMNS + """
                               , secret_ciphertext
                          FROM cp.webhooks
                         WHERE tenant_id = :tenant_id AND project_id = :project_id
                         ORDER BY id
                        """)
                .query((rs, row) -> new WithSecret(map(rs, row), rs.getBytes("secret_ciphertext")))
                .list();
    }

    /**
     * 删除。
     *
     * @param id Webhook ID
     */
    public void delete(String id) {
        sql("DELETE FROM cp.webhooks WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id")
                .param("id", id)
                .update();
    }

    private static Webhook map(ResultSet rs, int row) throws SQLException {
        return new Webhook(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("project_id"),
                rs.getString("url"),
                JdbcValues.readTextArray(rs, "events"),
                rs.getString("description"),
                rs.getBoolean("enabled"),
                rs.getString("created_by"),
                JdbcValues.instant(rs, "created_at"),
                JdbcValues.instant(rs, "updated_at"));
    }

    /**
     * 带签名密钥密文的 Webhook。
     *
     * @param webhook Webhook
     * @param secretCiphertext 签名密钥密文
     */
    public record WithSecret(Webhook webhook, byte[] secretCiphertext) {}
}
