---
paths:
  - "**/db/migration/**/*.sql"
  - "deploy/docker/postgres/**"
---

# SQL 与迁移规则（精要）

完整规范：`docs/engineering/coding-standards.md`「数据访问」「SQL」；表结构以架构设计「数据架构」为准。

- 迁移文件放在 `src/main/resources/db/migration/<schema>/`，命名 `V0001__描述.sql`，序号四位递增。已合入的迁移不修改，修正用新迁移。
- 文件头写一句目的；每张表、每个非显然的列用 `COMMENT ON` 写中文注释。
- 关键字大写，标识符小写 snake_case，表名复数；时间列 `timestamptz`；每张表有主键。
- 业务表带 `tenant_id`，项目级数据再带 `project_id`（作用域划分见架构设计「租户隔离」）。
- 分区表的主键与唯一约束必须包含分区键：`session_events` 按 `session_id` 哈希分区，`usage_records`、`audit_logs` 按 `occurred_at` 月分区且主键为 `(id, occurred_at)`。
- 同 schema 内用外键，跨 schema 不建外键；服务只读写自己的 schema。
- 并发控制写进 SQL 条件（如 `WHERE lease_epoch = :epoch`），调用方检查影响行数。
- 应用角色 `meridian_app` 只有 DML；DDL 只由 `meridian_migrator` 执行。
