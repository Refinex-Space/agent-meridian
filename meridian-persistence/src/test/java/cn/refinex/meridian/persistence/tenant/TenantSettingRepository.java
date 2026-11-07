package cn.refinex.meridian.persistence.tenant;

import java.util.List;
import javax.sql.DataSource;

/** 测试用的租户级仓储。 */
class TenantSettingRepository extends TenantScopedRepository {

    TenantSettingRepository(DataSource dataSource) {
        super(dataSource, TenantScope.TENANT);
    }

    void put(String name, String value) {
        sql("""
                INSERT INTO rt.t02_tenant_settings (tenant_id, name, value) VALUES (:tenant_id, :name, :value)
                ON CONFLICT (tenant_id, name) DO UPDATE SET value = EXCLUDED.value
                """).param("name", name).param("value", value).update();
    }

    List<String> values() {
        return sql("SELECT value FROM rt.t02_tenant_settings WHERE tenant_id = :tenant_id ORDER BY name")
                .query(String.class)
                .list();
    }

    int deleteAll() {
        return sql("DELETE FROM rt.t02_tenant_settings WHERE tenant_id = :tenant_id")
                .update();
    }
}
