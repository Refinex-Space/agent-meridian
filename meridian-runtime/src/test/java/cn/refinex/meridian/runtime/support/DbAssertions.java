package cn.refinex.meridian.runtime.support;

import cn.refinex.meridian.testsupport.TestDatabase;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 测试中直接读数据库的辅助：以迁移角色查询，与被测代码的应用角色分开。 */
public final class DbAssertions {

    private static final JdbcClient JDBC = JdbcClient.create(TestDatabase.migratorDataSource());

    private DbAssertions() {}

    /**
     * 读取一个轮次的全部列。
     *
     * @param turnId 轮次 ID
     * @return 列名到值
     */
    public static Map<String, Object> turn(String turnId) {
        return JDBC.sql("SELECT * FROM rt.turns WHERE id = :id")
                .param("id", turnId)
                .query()
                .singleRow();
    }

    /**
     * 读取租户的并发计数。
     *
     * @param tenantId 租户 ID
     * @return {@code running}；计数行不存在时为空
     */
    public static Integer running(String tenantId) {
        return JDBC.sql("SELECT running FROM rt.tenant_concurrency WHERE tenant_id = :id")
                .param("id", tenantId)
                .query(Integer.class)
                .optional()
                .orElse(null);
    }

    /**
     * 执行一条查询，返回第一列。
     *
     * @param sql SQL
     * @param params 位置参数
     * @param <T> 结果类型
     * @param type 结果类型
     * @return 结果
     */
    public static <T> List<T> list(String sql, Class<T> type, Object... params) {
        JdbcClient.StatementSpec spec = JDBC.sql(sql);
        for (Object param : params) {
            spec = spec.param(param);
        }
        return spec.query(type).list();
    }

    /**
     * 执行一条更新语句（以迁移角色）。
     *
     * @param sql SQL
     * @param params 位置参数
     * @return 影响的行数
     */
    public static int update(String sql, Object... params) {
        JdbcClient.StatementSpec spec = JDBC.sql(sql);
        for (Object param : params) {
            spec = spec.param(param);
        }
        return spec.update();
    }
}
