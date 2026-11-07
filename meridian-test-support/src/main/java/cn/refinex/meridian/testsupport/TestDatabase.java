package cn.refinex.meridian.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * 纯 JDBC 集成测试的数据库辅助：两个角色的数据源、按约定执行 Flyway 迁移、执行 SQL 脚本、清空 schema。
 *
 * <p>角色与生产一致：DDL 用迁移角色 {@code meridian_migrator}，被测代码用只有 DML 的应用角色 {@code meridian_app}，
 * 测试因此也能发现权限问题。迁移约定与 {@code meridian-persistence} 的 {@code flyway} 包一致：
 * 脚本在 {@code classpath:db/migration/<schema>/}，Flyway 的历史表在该 schema 内。
 */
public final class TestDatabase {

    /** schema 名的格式：拼进 SQL 前先校验，防止注入。 */
    private static final Pattern SCHEMA_NAME = Pattern.compile("[a-z_][a-z0-9_]*");

    /** Flyway 的历史表，清空 schema 时保留。 */
    private static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";

    private TestDatabase() {}

    /**
     * 应用角色的数据源，被测代码使用。每次调用返回新的无连接池数据源。
     *
     * @return 以 {@code meridian_app} 登录的数据源
     */
    public static DataSource appDataSource() {
        return dataSource(MeridianContainers.APP_USER, MeridianContainers.APP_PASSWORD);
    }

    /**
     * 迁移角色的数据源，用于建表、准备与清理数据。
     *
     * @return 以 {@code meridian_migrator} 登录的数据源
     */
    public static DataSource migratorDataSource() {
        return dataSource(MeridianContainers.MIGRATOR_USER, MeridianContainers.MIGRATOR_PASSWORD);
    }

    /**
     * 返回应用角色的 JDBC URL，供需要自建连接池的测试使用。
     *
     * @return JDBC URL
     */
    public static String jdbcUrl() {
        return MeridianContainers.postgres().getJdbcUrl();
    }

    /**
     * 按约定执行某个 schema 的 Flyway 迁移：脚本在 {@code classpath:db/migration/<schema>/}。
     *
     * @param schema {@code cp}、{@code rt} 或 {@code kb}
     */
    public static void migrate(String schema) {
        requireSchemaName(schema);
        Flyway.configure()
                .dataSource(migratorDataSource())
                .locations("classpath:db/migration/" + schema)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(false)
                .load()
                .migrate();
    }

    /**
     * 以迁移角色执行类路径上的 SQL 脚本，{@code search_path} 设为 {@code <schema>, public}，
     * 脚本中不带 schema 前缀的对象建在该 schema 中。用于测试 {@code meridian-persistence} 提供的建表片段。
     *
     * @param schema 目标 schema
     * @param classpathResource 脚本在类路径上的位置，例如 {@code cn/refinex/meridian/persistence/outbox/outbox.sql}
     */
    public static void runScript(String schema, String classpathResource) {
        requireSchemaName(schema);
        String sql = readResource(classpathResource);
        try (Connection connection = migratorDataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema + ", public");
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("执行脚本失败：" + classpathResource, e);
        }
    }

    /**
     * 清空某个 schema 中除 Flyway 历史表外的所有表（{@code TRUNCATE ... CASCADE}），以迁移角色执行。
     *
     * @param schema 目标 schema
     */
    public static void truncate(String schema) {
        requireSchemaName(schema);
        try (Connection connection = migratorDataSource().getConnection();
                Statement statement = connection.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = '" + schema
                    + "' AND tablename <> '" + FLYWAY_HISTORY_TABLE + "'")) {
                while (rs.next()) {
                    tables.add(schema + "." + rs.getString(1));
                }
            }
            if (!tables.isEmpty()) {
                statement.execute("TRUNCATE " + String.join(", ", tables) + " CASCADE");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("清空 schema 失败：" + schema, e);
        }
    }

    private static DataSource dataSource(String user, String password) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(jdbcUrl());
        dataSource.setUser(user);
        dataSource.setPassword(password);
        return dataSource;
    }

    private static void requireSchemaName(String schema) {
        if (schema == null || !SCHEMA_NAME.matcher(schema).matches()) {
            throw new IllegalArgumentException("非法的 schema 名：" + schema);
        }
    }

    private static String readResource(String resource) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("类路径上找不到脚本：" + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
