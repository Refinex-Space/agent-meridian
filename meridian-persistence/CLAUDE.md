# meridian-persistence

持久化基础设施：租户作用域仓储基类、outbox、Redis Stream 与 Pub/Sub 封装、S3 兼容对象存储、`Idempotency-Key`、迁移约定，以及它们的 Spring Boot 自动配置。不含业务表与业务仓储。

## 命令

- 编译与单测：`./mvnw -pl meridian-persistence -am test`
- 集成测试（Testcontainers，需要 Docker）：`./mvnw -pl meridian-persistence -am verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false`
- 单个集成测试：上一条再加 `-Dit.test=<类名> -Dfailsafe.failIfNoSpecifiedTests=false`

## 包地图

- `tenant`：`TenantScopedRepository`（`PROJECT`/`TENANT` 作用域）、`SystemRepository` + `@CrossTenant`、`ScopedStatement`。
- `jdbc`：`JdbcValues`（`jsonb`、`text[]`、`timestamptz` 转换）、`PersistenceAutoConfiguration`、`PeriodicJob`（随容器启停的后台任务）。
- `outbox`：`OutboxWriter`（调用方事务内写）、`OutboxRelay`（`SKIP LOCKED` 取出、发布、标记）、`OutboxPublisher`；建表片段 `src/main/resources/cn/refinex/meridian/persistence/outbox/outbox.sql`。
- `redis`：`RedisMessaging`（阻塞）、`ReactiveRedisMessaging`（响应式）、`RedisStreamOutboxPublisher`（主题即 Stream 键）。
- `objectstore`：`ObjectStore`，属性前缀 `meridian.persistence.objectstore`。
- `idempotency`：`Idempotency.execute(principal, key, hash, action)`；建表片段 `.../idempotency/idempotency.sql`。
- `flyway`：`MigrationConventions`，脚本在 `classpath:db/migration/<schema>/`。

设置 `meridian.persistence.schema`（`cp`、`rt`、`kb`）后自动配置才生效：迁移约定、默认 `Clock`、outbox、幂等键与清理任务；存在 `OutboxPublisher`（有 Redis 时自动提供）才创建 `OutboxRelay`。

## 不变量

- 业务仓储继承 `TenantScopedRepository`，SQL 必须引用 `:tenant_id`（项目级还要 `:project_id`），值只由基类从 `PlatformContext` 绑定，子类不能设置（架构设计「租户隔离」、ADR-0020）。
- `JdbcClient`/`JdbcTemplate` 只出现在两类基类的子类中；基类构造参数是 `DataSource`（ArchUnit 规则 8）。
- `SystemRepository` 子类标注 `@CrossTenant(reason)`，业务模块中只能放在 `infra.system`；本模块自己的子类豁免位置约束（规则 9、10）。
- 本模块的 SQL 都带 schema 前缀，schema 名先经 `SchemaNames.require` 校验再拼接。
- outbox 至少一次投递，消费方按条目字段 `id`（outbox 消息 ID）幂等，不按 Redis 条目 ID。

## 易错点

- `Instant` 不能直接作为 JDBC 参数：`ScopedStatement.param` 自动转换，`SystemRepository` 子类用 `JdbcValues.toSql` 或 `timestamptz`。
- `text[]` 参数传 `String[]`（`JdbcValues.textArray`），传 `List` 会被 Spring 展开成 IN 列表。
- `OutboxWriter.append` 必须在 Spring 事务内调用，否则抛异常。
- 含 OR 的 WHERE 条件要加括号，基类只检查作用域参数存在，不检查逻辑。
- 幂等记录不加入业务事务：业务抛 `RuntimeException` 时键被释放，正常返回的任何响应都会被保存并重放。
- 集成测试的表建在 `rt`，用应用角色执行；新建测试表写进 `src/test/resources/sql/persistence-test-tables.sql`。

## 对外接口

各服务继承仓储基类，注入 `OutboxWriter`、`RedisMessaging`/`ReactiveRedisMessaging`、`ObjectStore`、`Idempotency`；各服务的第一个迁移复制 `outbox.sql`、`idempotency.sql`。
