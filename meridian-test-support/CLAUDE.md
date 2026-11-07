# meridian-test-support

集成测试夹具：单例 Testcontainers 容器（PostgreSQL + pgvector + zhparser、Redis、MinIO）、Spring 属性注册、纯 JDBC 的迁移与清理。只作为其他模块的 test 作用域依赖。

## 命令

- 夹具自测：`./mvnw -pl meridian-test-support -am verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false`

## 包地图

- `MeridianContainers`：`postgres()`、`redis()`、`minio()`，首次访问时启动，同一 JVM 共用。
- `PostgresImage`：从 `deploy/docker/postgres/` 构建 `agent-meridian/postgres:test-<内容摘要>`，本机已有同摘要镜像时跳过构建。
- `TestDatabase`：`appDataSource()`（`meridian_app`，只有 DML）、`migratorDataSource()`、`migrate(schema)`、`runScript(schema, 资源)`、`truncate(schema)`。
- `SpringContainerProperties`：`@DynamicPropertySource` 中调用 `postgres`、`redis`、`minio`。

## 不变量

- 不依赖任何平台模块：`meridian-persistence` 的集成测试依赖本模块，反向依赖会形成 Maven 循环（ADR-0020）。
- 镜像版本与 `deploy/compose/compose.yaml` 一致，修改时两处一起改；MinIO 用按摘要固定的 Chainguard 镜像。
- 迁移约定与 `persistence.flyway.MigrationConventions` 一致：`classpath:db/migration/<schema>`，历史表在该 schema 内。

## 易错点

- 容器跨测试类共用，数据会保留：用随机 ID 隔离，或开始前 `truncate` 自己的 schema。
- 首次构建 PostgreSQL 镜像约 2 分钟；需要代理时设置 `MERIDIAN_DOCKER_BUILD_PROXY`（例如 `http://http.docker.internal:3128`）。
- 改了 `deploy/docker/postgres/` 下任何文件都会触发重建，旧的 `test-*` 镜像不会自动删除。
