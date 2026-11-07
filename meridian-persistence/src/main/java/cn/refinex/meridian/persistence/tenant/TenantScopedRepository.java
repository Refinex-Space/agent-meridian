package cn.refinex.meridian.persistence.tenant;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 租户作用域仓储基类：每条 SQL 都自动绑定当前 {@link PlatformContext} 的租户（项目级再加项目），
 * 见架构设计「租户隔离」与 ADR-0020。
 *
 * <p>设计目标是让"忘记带租户条件"写不出来：
 * <ul>
 *   <li>子类拿不到 {@link JdbcClient}，只能通过 {@link #sql(String)} 获得 {@link ScopedStatement}。
 *   <li>SQL 必须引用命名参数 {@code :tenant_id}（项目级还要 {@code :project_id}），缺少时直接抛异常，
 *       第一次执行（通常在测试中）就会暴露。
 *   <li>这两个参数的值只来自 {@link PlatformContexts#current()}，子类不能自行绑定或覆盖。
 *   <li>当前作用域没有上下文、或项目级仓储的上下文缺少项目时抛 {@link IllegalStateException}，不会静默放行。
 * </ul>
 * 子类写 SQL 时把作用域条件与其他条件用 AND 连接；含 OR 的条件要加括号，避免作用域条件被短路。
 *
 * <p>示例：
 * <pre>{@code
 * Optional<Session> find(String id) {
 *     return sql("SELECT * FROM rt.sessions WHERE tenant_id = :tenant_id AND project_id = :project_id AND id = :id")
 *             .param("id", id)
 *             .query(Session.class)
 *             .optional();
 * }
 * }</pre>
 *
 * <p>runtime 中的调用经 {@code common.concurrent.Blocking} 执行，它把 Reactor Context 中的上下文绑定到虚拟线程上。
 */
public abstract class TenantScopedRepository {

    /** 租户参数名，由基类绑定。 */
    public static final String TENANT_PARAM = "tenant_id";

    /** 项目参数名，由基类在项目级作用域下绑定。 */
    public static final String PROJECT_PARAM = "project_id";

    /** 匹配 {@code :tenant_id}，前面不能是冒号（排除 {@code ::类型转换}），后面不能接标识符字符。 */
    private static final Pattern TENANT_PLACEHOLDER = placeholder(TENANT_PARAM);

    private static final Pattern PROJECT_PLACEHOLDER = placeholder(PROJECT_PARAM);

    private final JdbcClient jdbc;
    private final TenantScope scope;

    /**
     * 创建租户作用域仓储。
     *
     * @param dataSource 应用的数据源；在 Spring 事务中执行时自动加入当前事务
     * @param scope 作用域：项目级或租户级
     */
    protected TenantScopedRepository(DataSource dataSource, TenantScope scope) {
        this.jdbc = JdbcClient.create(dataSource);
        this.scope = scope;
    }

    /**
     * 返回本仓储的作用域。
     *
     * @return 作用域
     */
    protected final TenantScope scope() {
        return scope;
    }

    /**
     * 创建一条带作用域的语句：校验 SQL 引用了作用域参数，并从当前上下文绑定它们。
     *
     * @param sql 使用命名参数的 SQL，必须包含 {@code :tenant_id}，项目级还必须包含 {@code :project_id}
     * @return 可以继续绑定参数并执行的语句
     * @throws IllegalArgumentException SQL 缺少作用域参数时
     * @throws IllegalStateException 当前作用域没有 {@link PlatformContext}，或项目级仓储的上下文没有项目时
     */
    protected final ScopedStatement sql(String sql) {
        requirePlaceholder(sql, TENANT_PLACEHOLDER, TENANT_PARAM);
        PlatformContext context = PlatformContexts.current();
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param(TENANT_PARAM, context.tenantId());
        if (scope == TenantScope.PROJECT) {
            requirePlaceholder(sql, PROJECT_PLACEHOLDER, PROJECT_PARAM);
            if (context.projectId() == null) {
                throw new IllegalStateException(
                        "项目级仓储 " + getClass().getSimpleName() + " 需要 PlatformContext.projectId");
            }
            statement = statement.param(PROJECT_PARAM, context.projectId());
        }
        return new ScopedStatement(statement);
    }

    private void requirePlaceholder(String sql, Pattern pattern, String name) {
        if (!pattern.matcher(sql).find()) {
            throw new IllegalArgumentException(
                    getClass().getSimpleName() + "（" + scope + " 作用域）的 SQL 必须引用 :" + name + "：" + sql);
        }
    }

    private static Pattern placeholder(String name) {
        return Pattern.compile("(?<!:):" + name + "(?![A-Za-z0-9_])");
    }
}
