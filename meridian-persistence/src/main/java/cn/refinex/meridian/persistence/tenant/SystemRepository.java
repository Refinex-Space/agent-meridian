package cn.refinex.meridian.persistence.tenant;

import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 跨租户的系统仓储基类：查询不带租户条件，用于认证查找、调度、超时扫描、outbox 投递、用量与审计消费者、
 * 对账、事件归档等后台作业，以及按自身键隔离的端口实现，见架构设计「租户隔离」。
 *
 * <p>约束（{@code meridian-arch-tests} 检查）：
 * <ul>
 *   <li>子类必须标注 {@link CrossTenant} 并写明理由。
 *   <li>业务模块中的子类只能放在各上下文的 {@code infra.system} 包。
 *   <li>{@code api} 包不能依赖子类。
 * </ul>
 *
 * <p>子类通过 {@link #jdbc()} 拿到 {@link JdbcClient}，自己负责 SQL 的全部条件；
 * {@code Instant} 参数用 {@code JdbcValues.toSql} 转换。
 */
public abstract class SystemRepository {

    private final JdbcClient jdbc;

    /**
     * 创建系统仓储。传入数据源而不是 {@code JdbcClient}，使 {@code JdbcClient} 只出现在两类仓储基类的子类中。
     *
     * @param dataSource 应用的数据源；在 Spring 事务中执行时自动加入当前事务
     */
    protected SystemRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * 返回不带任何作用域限制的 {@link JdbcClient}。
     *
     * @return JDBC 客户端
     */
    protected final JdbcClient jdbc() {
        return jdbc;
    }
}
