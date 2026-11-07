# T02 通用基础库、持久化与本地基础设施

| 项 | 内容 |
|---|---|
| 阶段 | 0 工程基座 |
| 依赖 | T01 |
| 推荐配置 | Opus 5.5，effort high，新会话 |
| 规模 | 大 |
| 关联验证项 | 无 |

启动方式：在仓库根目录开新会话，输入 `/task T02`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；AgentScope 只以 2.0.4 为准（读法见 `docs/plan/references.md`）。
- 只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

后续每个模块都会用到 ID、错误、分页、JSON、时钟、平台上下文、租户作用域仓储、outbox、Redis 流、对象存储。编码规范的「通用能力复用」表规定了它们的唯一位置。本任务把这些能力一次实现到位，让后面的任务只需复用，不会各写一套。

同时搭好本地基础设施：带 pgvector 与中文分词的 PostgreSQL、Redis、MinIO，以及集成测试用的容器夹具。

## 必读

1. 架构设计：「架构原则」「数据架构」（全节）「安全架构」中的「租户隔离」「API 设计」中的「约定」。
2. `docs/engineering/coding-standards.md`：「通用能力复用」「数据访问」「并发与响应式」「错误处理」「日志」。
3. `docs/engineering/testing.md`：「集成测试夹具」「本机环境」。

## 范围

### 要做

- `meridian-common`、`meridian-persistence`、`meridian-test-support` 的实现。
- `meridian-security` 中的 `web` 包：请求 ID 与 problem+json 映射（认证在 T07、T14a 和 T14b）。
- PostgreSQL 镜像、Docker Compose、启停脚本。
- ADR-0020：持久化技术选型与上下文传递方式。

### 不做

- 任何业务表与业务仓储（各服务的任务负责）。
- 认证、鉴权、服务 JWT（T07、T14a、T14b）。

## 交付物

### 1. `meridian-common`

| 包 | 内容 |
|---|---|
| `id` | `IdPrefix` 枚举（架构设计「ID 规则」中的全部前缀），`Ids`：生成 `{前缀}_{ULID}`、解析、校验前缀 |
| `error` | `ErrorCode`（接口加注册约定，码值形如 `session.not_found`，带 HTTP 状态与默认标题），`MeridianException` 及少量子类（未找到、冲突、校验失败、权限不足、租约丢失） |
| `page` | `Cursor`（base64url 编码的不透明游标）、`PageRequest`、`PageResult<T>`（`data`、`has_more`、`next_cursor`） |
| `json` | Jackson 3 `JsonMapper` 工厂：snake_case、RFC 3339 UTC 时间、忽略未知字段的策略按用途区分（入站严格、出站宽松） |
| `time` | 约定使用注入的 `Clock`；提供测试用的可控时钟 |
| `context` | `PlatformContext`（租户、项目、会话、线程、轮次、API Key、租约 epoch、预算句柄、`interaction`，按架构设计「身份映射」）；`PlatformContexts`：MVC 侧基于 `ScopedValue` 的绑定与读取，WebFlux 侧基于 Reactor Context 的写入与读取 |
| `concurrent` | `Blocking`：把阻塞调用调度到虚拟线程，返回 `Mono`；在 Reactor 线程上调用阻塞 API 时，由它承接 |

### 2. `meridian-persistence`

| 包 | 内容 |
|---|---|
| `jdbc` | 基于 Spring `JdbcClient` 的公共配置：数据源、事务模板、jsonb 与数组的读写、`timestamptz` 与 `Instant` 映射 |
| `tenant` | 按架构设计「租户隔离」的三类基类：`TenantScopedRepository`（子类在构造时声明项目级或租户级作用域，从 `PlatformContext` 取 `tenant_id`，项目级再取 `project_id`，在查询与写入中强制带上；缺少上下文时抛异常，不静默放行）；`SystemRepository`（跨租户，供后台作业与认证查找使用）与注解 `@CrossTenant(reason)` |
| `outbox` | `OutboxWriter`（在调用方事务内写 `outbox` 表）、`OutboxRelay`（`SKIP LOCKED` 批量取出、发布、标记）、`OutboxPublisher` 接口（默认实现发布到 Redis Stream） |
| `redis` | Redis Stream 的发布、按消费组消费（含确认与重投）、Pub/Sub 发布订阅的薄封装；响应式与阻塞两种客户端 |
| `objectstore` | S3 兼容对象存储客户端（AWS SDK v2，兼容 MinIO）：上传、下载、删除、按前缀删除、预签名 URL |
| `idempotency` | `Idempotency-Key` 的通用实现：24 小时内同键同请求体返回首次结果，同键不同请求体返回冲突；每个服务在自己的 schema 中建幂等表（本模块提供建表 SQL 片段） |
| `flyway` | 约定：每个应用的迁移在 `src/main/resources/db/migration/<schema>/`；迁移用迁移角色执行，应用用只有 DML 权限的角色 |

`outbox` 表由使用它的服务各自建在自己的 schema 中；本模块提供建表 SQL 片段与说明，供各服务的第一个迁移引用。

### 3. `meridian-security` 的 `web` 包

- 请求 ID：读取或生成 `X-Request-Id`，写入 MDC 与响应头。
- problem+json：把 `MeridianException` 与常见框架异常映射为 RFC 9457 响应，带 `code` 与 `request_id`；MVC 与 WebFlux 各一个适配器，映射逻辑只有一份。

### 4. 本地基础设施

- `deploy/docker/postgres/Dockerfile`：基于官方 pgvector 的 PostgreSQL 17 镜像，编译安装 zhparser（及其依赖 SCWS）。SCWS 与 zhparser 固定版本并校验 SHA-256。构建时宿主 shell 的代理变量在容器里不可用，需要代理时用 `--build-arg HTTPS_PROXY=http://http.docker.internal:3128`（Docker Desktop 内置代理，与 `scripts/dev/kind-gvisor.sh` 一致）。初始化脚本：
  - 创建 `meridian_migrator`（DDL）与 `meridian_app`（只有 DML）两个角色，以及 `cp`、`rt`、`kb` 三个 schema。
  - 启用 `vector` 与 `zhparser` 扩展，创建中文全文检索配置 `chinese`。
- `deploy/compose/compose.yaml`：PostgreSQL（上面的镜像）、Redis、MinIO 及初始化桶的一次性容器。全部带健康检查。可选组件用 profile 隔离，本任务不加入。
- `scripts/dev/up.sh` 与 `scripts/dev/down.sh`：启动并等待健康、停止；`down.sh --volumes` 才删数据。
- 本地开发的连接信息写在 `deploy/compose/.env.example`，不提交真实的 `.env`。

### 5. `meridian-test-support`

- Testcontainers 单例容器：PostgreSQL（从上面的 Dockerfile 构建，构建结果缓存）、Redis、MinIO。
- 为 Spring 测试提供连接属性的注册方式；给纯 JDBC 测试提供迁移与清理的辅助方法。

### 6. ADR-0020

`docs/adr/0020-持久化与上下文传递.md`（编号已预留），状态"已采纳（补充）"，记录：统一使用 `JdbcClient`，不引入 JPA 与 MyBatis；三类仓储基类的划分与 `@CrossTenant` 的使用规则；MVC 服务用 `ScopedValue`、runtime 用 Reactor Context 传递 `PlatformContext`；`meridian-security` 承担 Web 入口的公共能力（请求 ID、错误映射、PlatformContext 填充）。写明理由与被否决的方案。

### 7. 架构规则

在 `meridian-arch-tests` 中补上 T01 登记的规则（架构设计「租户隔离」）：`JdbcClient` 与 `JdbcTemplate` 只能被 `TenantScopedRepository` 与 `SystemRepository` 的子类依赖；`SystemRepository` 的子类只能在 `..infra.system..` 包中，且必须标注 `@CrossTenant`；`..api..` 包不能依赖 `SystemRepository` 的子类。

## 实现要点

- `TenantScopedRepository` 的设计目标是让"忘记带租户条件"在代码上写不出来：子类只能通过基类提供的方法拿到带作用域的查询入口。不要设计成"约定子类自己记得加 WHERE"。
- `OutboxRelay` 必须可以多实例并发运行而不重复投递同一条（靠 `SKIP LOCKED` 与发布后标记）；消费方按消息 ID 幂等。
- `Blocking` 用 JDK 25 的虚拟线程执行器；BlockHound 测试证明 Reactor 线程上直接调用 JDBC 会被检测到，经 `Blocking` 调用则不会。
- 不要为将来可能用到的能力预先实现，只做上表列出的。

## 验收标准

- [T02-A01] `Ids` 生成的 ID 前缀正确、按时间有序、可解析，非法前缀被拒绝。验证：单元测试。
- [T02-A02] problem+json 映射在 MVC 与 WebFlux 中输出相同结构，包含 `type`、`title`、`status`、`detail`、`code`、`request_id`。验证：两个适配器的测试。
- [T02-A03] 游标分页编码、解码互逆，篡改的游标被拒绝。验证：单元测试。
- [T02-A04] `TenantScopedRepository`：项目级与租户级两种作用域下，租户 A 的上下文读不到、改不到租户 B 的数据；项目级下同租户的其他项目也不可见；缺少上下文时抛异常。验证：集成测试。
- [T02-A05] outbox：业务事务回滚时消息不发布；两个 relay 实例并发运行时每条消息只发布一次；消费方重复收到时幂等。验证：集成测试。
- [T02-A06] `Blocking` 与 BlockHound：Reactor 线程上直接调用阻塞 JDBC 被检测为违规，经 `Blocking` 调用通过。验证：测试。
- [T02-A07] 对象存储客户端在 MinIO 上完成上传、下载、按前缀删除、预签名下载。验证：集成测试。
- [T02-A08] `scripts/dev/up.sh` 启动后全部容器健康；`SELECT to_tsvector('chinese', '差旅住宿标准')` 返回非空的分词结果；`vector` 扩展可用；`meridian_app` 角色执行 DDL 被拒绝。验证：命令与输出。
- [T02-A09] 新增的架构规则生效：临时写一个不继承两类基类却注入 `JdbcClient` 的类、一个放在 `infra.system` 之外的 `SystemRepository` 子类、一个缺少 `@CrossTenant` 的 `SystemRepository` 子类，架构测试分别失败；撤销后通过。验证：关键输出。
- [T02-A10] ADR-0020 已写入，内容完整。验证：文件路径。
- [T02-A11] `scripts/dev/verify.sh` 通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 为 `meridian-common`、`meridian-persistence`、`meridian-security`、`meridian-test-support` 创建模块 `CLAUDE.md`。
- 在根 `CLAUDE.md` 的命令一节加入 `scripts/dev/up.sh`、`down.sh`。
- 测试结束后执行 `scripts/dev/down.sh`，不留下运行中的容器。
