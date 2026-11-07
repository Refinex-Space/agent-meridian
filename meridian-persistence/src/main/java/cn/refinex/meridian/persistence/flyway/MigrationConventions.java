package cn.refinex.meridian.persistence.flyway;

import cn.refinex.meridian.persistence.jdbc.SchemaNames;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;

/**
 * 把迁移约定应用到 Spring Boot 的 Flyway 配置（编码规范「数据访问」）：
 * <ul>
 *   <li>脚本在 {@code classpath:db/migration/<schema>/}，命名 {@code V0001__描述.sql}；
 *   <li>迁移只作用于服务自己的 schema，历史表也在其中；schema 由数据库初始化脚本预先创建，Flyway 不创建；
 *   <li>迁移用 {@code meridian_migrator}（{@code spring.flyway.user}），应用用只有 DML 的 {@code meridian_app}
 *       （{@code spring.datasource.username}）。
 * </ul>
 * 约定覆盖 {@code spring.flyway.locations} 与 {@code spring.flyway.schemas} 的设置，保证三个服务一致。
 * {@code meridian-test-support} 的 {@code TestDatabase.migrate} 按同一约定执行。
 */
public final class MigrationConventions implements FlywayConfigurationCustomizer {

    private final String schema;

    /**
     * 创建约定。
     *
     * @param schema 服务的 schema：{@code cp}、{@code rt} 或 {@code kb}
     */
    public MigrationConventions(String schema) {
        this.schema = SchemaNames.require(schema);
    }

    /**
     * 返回某个 schema 的迁移脚本位置。
     *
     * @param schema 服务的 schema
     * @return 形如 {@code classpath:db/migration/rt} 的位置
     */
    public static String location(String schema) {
        return "classpath:db/migration/" + SchemaNames.require(schema);
    }

    @Override
    public void customize(FluentConfiguration configuration) {
        configuration
                .locations(location(schema))
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(false);
    }
}
