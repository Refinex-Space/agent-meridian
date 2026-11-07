package cn.refinex.meridian.persistence.tenant;

import cn.refinex.meridian.persistence.jdbc.JdbcValues;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 已绑定租户作用域的 SQL 语句，由 {@link TenantScopedRepository#sql(String)} 创建。
 *
 * <p>只暴露绑定业务参数与执行的方法；作用域参数 {@code tenant_id}、{@code project_id} 不能再次绑定。
 * {@link java.time.Instant} 参数自动转换为 {@code timestamptz}（见 {@link JdbcValues#toSql(Object)}）。
 *
 * <p>线程安全：不可共享，每次 {@code sql(...)} 创建一个新实例。
 */
public final class ScopedStatement {

    private final JdbcClient.StatementSpec statement;

    ScopedStatement(JdbcClient.StatementSpec statement) {
        this.statement = statement;
    }

    /**
     * 绑定一个命名参数。
     *
     * @param name 参数名，不能是 {@code tenant_id} 或 {@code project_id}
     * @param value 参数值，可为空
     * @return 本语句
     * @throws IllegalArgumentException 试图绑定作用域参数时
     */
    public ScopedStatement param(String name, Object value) {
        if (TenantScopedRepository.TENANT_PARAM.equals(name) || TenantScopedRepository.PROJECT_PARAM.equals(name)) {
            throw new IllegalArgumentException(
                    "作用域参数 :" + name + " 由 TenantScopedRepository 从 PlatformContext 绑定，不能手动设置");
        }
        statement.param(name, JdbcValues.toSql(value));
        return this;
    }

    /**
     * 以行映射器执行查询。
     *
     * @param rowMapper 行映射器
     * @param <T> 结果类型
     * @return 查询规格，可以取 {@code list()}、{@code optional()}、{@code single()} 等
     */
    public <T> JdbcClient.MappedQuerySpec<T> query(RowMapper<T> rowMapper) {
        return statement.query(rowMapper);
    }

    /**
     * 映射为简单类型或 record：单列结果映射为该类型，多列结果按列名（snake_case 对应 camelCase）映射为 record 组件。
     *
     * @param type 结果类型
     * @param <T> 结果类型
     * @return 查询规格
     */
    public <T> JdbcClient.MappedQuerySpec<T> query(Class<T> type) {
        return statement.query(type);
    }

    /**
     * 执行 INSERT、UPDATE、DELETE。
     *
     * @return 影响的行数；需要并发控制的更新由调用方检查它（编码规范「SQL」）
     */
    public int update() {
        return statement.update();
    }
}
