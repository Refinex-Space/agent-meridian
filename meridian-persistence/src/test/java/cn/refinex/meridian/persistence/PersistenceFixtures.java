package cn.refinex.meridian.persistence;

import cn.refinex.meridian.testsupport.TestDatabase;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * persistence 集成测试的公共准备：在 rt schema 中执行本模块提供的建表片段与测试表。
 */
public final class PersistenceFixtures {

    /** 测试使用的 schema。 */
    public static final String SCHEMA = "rt";

    private static boolean initialized;

    private PersistenceFixtures() {}

    /** 建表，同一个 JVM 内只执行一次；片段本身带 IF NOT EXISTS，重复执行也安全。 */
    public static synchronized void ensureTables() {
        if (initialized) {
            return;
        }
        TestDatabase.runScript(SCHEMA, "cn/refinex/meridian/persistence/outbox/outbox.sql");
        TestDatabase.runScript(SCHEMA, "cn/refinex/meridian/persistence/idempotency/idempotency.sql");
        TestDatabase.runScript(SCHEMA, "sql/persistence-test-tables.sql");
        initialized = true;
    }

    /** 被测代码使用的数据源：只有 DML 权限的应用角色，与生产一致。 */
    public static DataSource dataSource() {
        ensureTables();
        return TestDatabase.appDataSource();
    }

    /** 与 {@link #dataSource()} 对应的事务管理器。 */
    public static DataSourceTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
