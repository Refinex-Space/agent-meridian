package cn.refinex.meridian.persistence.idempotency;

import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import cn.refinex.meridian.persistence.jdbc.SchemaNames;
import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import java.time.Instant;
import javax.sql.DataSource;

/**
 * 删除过期的幂等键记录，防止表无限增长。过期记录在同键再次使用时也会被覆盖，清理只是回收空间。
 */
@CrossTenant(reason = "过期清理是跨租户的后台作业，只按 expires_at 删除，不读取记录内容")
public class IdempotencyPurger extends SystemRepository {

    private final String purgeSql;

    /**
     * 创建清理器。
     *
     * @param dataSource 应用的数据源
     * @param schema 幂等键表所在的 schema
     */
    public IdempotencyPurger(DataSource dataSource, String schema) {
        super(dataSource);
        String table = SchemaNames.require(schema) + ".idempotency_keys";
        this.purgeSql = "DELETE FROM " + table + " WHERE ctid IN (SELECT ctid FROM " + table
                + " WHERE expires_at <= :now LIMIT :limit)";
    }

    /**
     * 删除已过期的记录。
     *
     * @param now 当前时间
     * @param limit 本次最多删除的条数
     * @return 删除的条数
     */
    public int purgeExpired(Instant now, int limit) {
        return jdbc().sql(purgeSql)
                .param("now", JdbcValues.timestamptz(now))
                .param("limit", limit)
                .update();
    }
}
