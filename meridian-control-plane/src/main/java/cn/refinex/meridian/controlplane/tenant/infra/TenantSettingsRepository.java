package cn.refinex.meridian.controlplane.tenant.infra;

import cn.refinex.meridian.controlplane.tenant.domain.TenantSettings;
import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.tenant.TenantScope;
import cn.refinex.meridian.persistence.tenant.TenantScopedRepository;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/** {@code cp.tenant_settings} 的租户级仓储：每个租户至多一行，没有行表示默认值。 */
@Repository
public class TenantSettingsRepository extends TenantScopedRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public TenantSettingsRepository(DataSource dataSource) {
        super(dataSource, TenantScope.TENANT);
    }

    /**
     * 读取当前租户的设置。
     *
     * @return 设置；从未设置时为空
     */
    public Optional<TenantSettings> find() {
        return sql("SELECT * FROM cp.tenant_settings WHERE tenant_id = :tenant_id")
                .query((rs, row) -> new TenantSettings(
                        rs.getString("system_prompt"),
                        JdbcValues.readJsonb(rs, "web_search", TenantSettings.WebSearch.class),
                        JdbcValues.instant(rs, "updated_at")))
                .optional();
    }

    /**
     * 整体替换当前租户的设置。
     *
     * @param settings 新设置，{@code updatedAt} 必填
     */
    public void upsert(TenantSettings settings) {
        sql("""
                        INSERT INTO cp.tenant_settings (tenant_id, system_prompt, web_search, updated_at)
                        VALUES (:tenant_id, :prompt, :web_search, :now)
                        ON CONFLICT (tenant_id) DO UPDATE
                           SET system_prompt = EXCLUDED.system_prompt, web_search = EXCLUDED.web_search,
                               updated_at = EXCLUDED.updated_at
                        """)
                .param("prompt", settings.systemPrompt())
                .param("web_search", settings.webSearch() == null ? null : JdbcValues.jsonbOf(settings.webSearch()))
                .param("now", settings.updatedAt())
                .update();
    }
}
