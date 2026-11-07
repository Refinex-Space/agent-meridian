package cn.refinex.meridian.runtime.support;

import cn.refinex.meridian.testsupport.MeridianContainers;
import cn.refinex.meridian.testsupport.TestDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * runtime 集成测试的数据库：同一个 JVM 内执行一次 {@code rt} 迁移（以迁移角色），被测代码使用应用角色的连接池，
 * 与生产的角色划分一致。并发测试需要多个连接，所以用连接池而不是每次新建连接的数据源。
 */
public final class RuntimeTestDatabase {

    /** 连接池上限：足够并发测试中的多个 Worker 与写入线程同时持有连接。 */
    private static final int POOL_SIZE = 40;

    private static HikariDataSource dataSource;

    private RuntimeTestDatabase() {}

    /**
     * 返回应用角色的连接池，第一次调用时执行迁移。
     *
     * @return 数据源
     */
    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            TestDatabase.migrate("rt");
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(TestDatabase.jdbcUrl());
            config.setUsername(MeridianContainers.APP_USER);
            config.setPassword(MeridianContainers.APP_PASSWORD);
            config.setMaximumPoolSize(POOL_SIZE);
            config.setPoolName("runtime-test");
            dataSource = new HikariDataSource(config);
        }
        return dataSource;
    }

    /**
     * 返回绑定在 {@link #dataSource()} 上的事务模板（传播行为 REQUIRED）。
     *
     * @return 事务模板
     */
    public static TransactionTemplate transactions() {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource()));
    }

    /** 清空 {@code rt} 中的全部业务表。认领与回收跨租户运行，相关测试在开始前清空，避免读到其他测试类留下的轮次。 */
    public static void truncate() {
        dataSource();
        TestDatabase.truncate("rt");
    }
}
