# 编码规范

本规范约束 AgentMeridian 仓库中所有 Java、SQL、Python 代码，以及提交方式。前端规范见 [frontend-design.md](frontend-design.md)，测试规范见 [testing.md](testing.md)。

优先级：[架构设计](../architecture/architecture.md) > 本规范 > 模块 `CLAUDE.md`。三者冲突时以前者为准，并修正后者。

## 包与目录规划

### Java 包结构

包根为 `cn.refinex.meridian`，第二段是模块短名，第三段是业务上下文，第四段是分层：

```text
cn.refinex.meridian.<模块>.<上下文>.<分层>
例：cn.refinex.meridian.runtime.dispatch.infra
```

| 模块 | 短名 | 说明 |
|---|---|---|
| `meridian-common` | `common` | 按能力分包：`id`、`error`、`page`、`json`、`time`、`context`、`concurrent` |
| `meridian-security` | `security` | `apikey`、`oidc`（控制台用户身份）、`rbac`（角色矩阵与声明式授权）、`servicejwt`、`web`（请求 ID、错误映射、PlatformContext 填充） |
| `meridian-persistence` | `persistence` | `jdbc`、`tenant`、`outbox`、`redis`、`objectstore`、`idempotency`、`flyway` |
| `meridian-engine-spi` | `engine.spi` | `engine`、`port`、`event`、`spec` |
| `meridian-engine-agentscope` | `engine.agentscope` | 按架构设计"包结构"一节：`assembly`、`model`、`state`、`fs`、`tools`、`middleware`、`events`、`sandbox` |
| 三个应用 | `controlplane`、`runtime`、`knowledge` | 按业务上下文分包，每个上下文内部四层 |

应用内的四层：

| 层 | 放什么 | 命名 | 依赖规则 |
|---|---|---|---|
| `api` | HTTP 处理器，实现由 OpenAPI 生成的接口；只做 decode → call → map | `*ApiController`、`*Mapper` | 只依赖 `app` |
| `app` | 用例服务，编排领域对象和基础设施，定义事务边界 | `*Service` | 依赖 `domain`，通过接口依赖 `infra` |
| `domain` | 实体、值对象、领域规则、领域事件，纯 Java | 名词，如 `Turn`、`LeasePolicy` | 不依赖 Spring、不依赖 `infra` |
| `infra` | 数据库仓储、外部 HTTP 客户端、Redis、对象存储 | `*Repository`、`*Client`、`*Publisher` | 实现 `app` 或端口定义的接口 |

配置类放在应用根包下的 `config` 包，命名 `*Configuration`；配置属性用 `record` 加 `@ConfigurationProperties`，命名 `*Properties`，前缀 `meridian.<模块>.<上下文>`。

### 每个包都有 package-info.java

内容是一段中文 Javadoc：这个包负责什么、不负责什么、允许依赖谁。它是给读代码的人和 AI 的地图，比在别处写说明更不容易过期。

```java
/**
 * 轮次调度：认领、续租、回收与租户并发计数。
 *
 * <p>所有 SQL 都跨租户运行，是 {@code TenantScopedRepository} 豁免清单的一部分，
 * 见架构设计「租户隔离」。本包不执行智能体，执行交给 {@code session.app.TurnWorker}。
 */
package cn.refinex.meridian.runtime.dispatch;
```

## 通用能力复用

**同一能力只实现一次。** 写任何工具方法、基础类、客户端之前，先在下表对应模块里搜索；有就复用，没有就在表里指定的位置新增，不在业务代码里就地实现。

| 能力 | 唯一位置 | 说明 |
|---|---|---|
| 带前缀的 ULID（生成、解析、校验） | `common.id` | `Ids.newId(IdPrefix.SESSION)` |
| 错误码与平台异常 | `common.error` | `MeridianException`、`ErrorCode` |
| problem+json 映射、请求 ID、`traceparent` | `security.web` | MVC 和 WebFlux 各一个适配器，共用同一套映射逻辑 |
| 游标分页 | `common.page` | `Cursor`、`PageResult` |
| JSON 序列化配置（snake_case、时间格式） | `common.json` | 平台 API 用 Jackson 3 |
| SHA-256 摘要的十六进制 | `common.crypto` | `Sha256.hex(bytes / text / Path)`；要原始字节、截断或分段更新时直接用 `MessageDigest`（R1 Q-L1） |
| 按字符数估算 token | `common.text` | `TextTokens`：内核的 `TokenEstimator` 与 runtime 的 Embedding、rerank 共用（R1 Q-M1） |
| 首包之前的供应商重试 | `engine.spi.port` | `ProviderRetryPolicy`：`RoutedModel` 与 `VectorModelService` 共用的次数、指数退避与 `Retry-After` 上限（R1 Q-M2） |
| 时钟 | `common.time` | 注入 `java.time.Clock`，禁止直接 `Instant.now()` |
| `PlatformContext` 与传递 | `common.context` | MVC 服务用 `ScopedValue`；runtime 用 Reactor Context |
| 租户作用域仓储、跨租户系统仓储 | `persistence.tenant` | `TenantScopedRepository`（项目级或租户级）、`SystemRepository` 加 `@CrossTenant` |
| outbox 写入与投递 | `persistence.outbox` | — |
| Redis Stream 发布与消费、Pub/Sub | `persistence.redis` | `StreamConsumer`：消费组循环、认领重投、死信回调、确认后裁剪（ADR-0048） |
| 四级预算的级别、周期与 Redis 键 | `common.budget` | `BudgetLevel`、`BudgetKeys`：control-plane 写限额与对账、runtime 预扣与结算共用（ADR-0048） |
| 审计消息的操作者 | `common.audit` | `AuditActor`：control-plane 与 runtime 的审计消息共用（ADR-0048） |
| 对象存储客户端 | `persistence.objectstore` | S3 兼容 |
| 阻塞调用的虚拟线程调度 | `common.concurrent` | `Blocking.call(...)`，runtime 中所有 JDBC 都经过它；阻塞的流与工具执行 `subscribeOn(Blocking.scheduler())`，不用有线程上限的 `Schedulers.boundedElastic()`（P7） |
| HTTP 幂等键（`Idempotency-Key`） | `persistence.idempotency` | 各服务共用，每个服务在自己的 schema 中建幂等表 |
| API Key 校验、服务 JWT、OIDC | `security` | `security.apikey`：`ApiKeyResolver` 端口、`ControlPlaneApiKeyResolver`（经 control-plane 校验，缓存 60 秒）、`ApiKeyHashes`、WebFlux 过滤器、作用域检查 `ApiKeyAuthorization`；`security.oidc`：`OidcTokenVerifier`（标准 OIDC，声明映射可配置）、`/admin/` 过滤器、公开 API 上的控制台用户（`ConsoleApiAuthenticator`、`ControlPlaneProjectAccessResolver`）；`security.servicejwt.ServiceJwtVerifier`：服务 JWT 的校验（ADR-0044） |
| 按 API Key 限流 | `security.apikey` | `ApiKeyRateLimiter` 端口与 Redis 令牌桶 `RedisApiKeyRateLimiter`，`RateLimit-*` 与 429 由过滤器输出（ADR-0042） |
| 角色矩阵与管理 API 的授权 | `security.rbac` | `RoleMatrix`、`@RequiresPermission`、MVC 拦截器；角色绑定由应用实现 `GrantSource`，业务代码不写角色判断 |
| 服务间 HTTP 调用约定（超时、重试、服务 JWT 注入） | `security.servicejwt` | `ServiceRestClients.create(baseUrl, audience)`：基于 Spring `RestClient`，超时与按目标服务换取并缓存的服务 JWT（`ServiceTokenClient`）；`/internal/` 由两种 Web 栈的过滤器保护，处理器首行 `ServiceCredentialAuthorization.require(exchange, 调用方…)` 或 `ServiceCredentialFilter.require(request, 调用方…)` 限定调用方（ADR-0044） |
| JWT 的签名与校验、JWKS | Nimbus JOSE + JWT | 不自写 JWS 解析与 JWKS 缓存：校验经 `ServiceJwtVerifier`、`OidcTokenVerifier`（`JWKSourceBuilder` 负责缓存与未知 `kid` 刷新），签发只在 control-plane 的 `servicetoken` 上下文 |
| SSRF 地址策略（地址分类、IP 字面量的全部写法、配置端点的校验） | `common.net` | `AddressPolicy.classify`/`isBlocked`/`literal`（`web_fetch` 逐跳判定），`EndpointPolicy`（供应商与搜索后端的 `base_url`：危险地址始终拒绝，私有网段按配置放行，ADR-0045；Webhook 地址同样用它，默认不放行内网，ADR-0049）；runtime 发出的 HTTP 在连接时校验解析结果用 `tooling.infra.GuardedResolverGroup`（`web_fetch`、Webhook 投递） |
| 平台工具基类 | `engine.agentscope.tools` | 所有平台工具继承同一个抽象基类 |

判断规则：

- 只有一个调用方的逻辑，写在调用方内部，不提前抽象。
- 第二个模块需要同一能力时，把它移到上表的位置，并让两处都改用它。
- 上表没有覆盖的跨模块能力，先在本表加一行，再写代码；涉及新模块或改变模块依赖时，走 ADR。

## 注释规范

注释用中文。代码标识符、协议名、类名、英文专有名词（lease、fencing、outbox）保留原文，首次出现可以附中文解释。

### 必须写注释的地方

| 位置 | 要求 |
|---|---|
| `public` 和 `protected` 的类、接口、枚举、record | Javadoc 首句说明"它是什么、负责什么"；涉及并发、事务、租约等约束时，写明约束和理由，并引用架构设计章节名 |
| `public` 方法 | 首句说明做什么；参数、返回值、异常在含义不自明时用 `@param`、`@return`、`@throws` 说明；有副作用的写明副作用 |
| record 组件、DTO 字段、实体字段、配置属性 | 说明含义；有单位的写单位，有取值范围的写范围，可空的写"可为空，表示……" |
| 枚举常量 | 每个常量一句说明 |
| 常量 | 说明取值来源，例如"架构设计「租约」规定 30 秒" |
| 私有方法和复杂逻辑 | 逻辑不自明时，写"为什么这样做"，不复述代码 |
| SQL 迁移 | 文件头说明目的；每张表、每个非显然的列，用 `COMMENT ON` 写中文注释 |

示例：

```java
/**
 * 带租约校验的智能体状态存储。
 *
 * <p>AgentScope 在中断、出错、停机等多条路径上都会写状态，而 HarnessAgent 不暴露冲突策略，
 * 所以 fencing 只能在存储层实现：每次写入都在同一事务里校验当前节点仍持有该线程的租约，
 * 见架构设计「租约与 fencing」。
 *
 * <p>线程安全：无状态，可被所有会话共享。
 */
public final class FencedAgentStateStore implements AgentStateStore {

    /** 租约过期时长。架构设计「租约」规定 30 秒，续租周期为其三分之一。 */
    static final Duration LEASE_TTL = Duration.ofSeconds(30);
```

### 不写的注释

- 复述代码的注释，例如 `// 遍历列表`、`// 设置名称`。
- `@author`、`@date`、版权头、"Created by"。
- 被注释掉的代码。删掉，git 里有历史。
- 无归属的 TODO。确需保留的写成 `// TODO(T15b): 接入真实配置源`，括号里是负责完成它的任务编号；任务交付时，自己任务编号的 TODO 必须清零。

只给你新写或修改的代码加注释，不顺手补其他代码的注释。

## 命名

- Java 标准命名。缩写按单词处理：`HttpClient`、`SseEmitter`、`apiKeyId`，不写 `HTTPClient`。
- ID 字段统一 `String xxxId`，值带前缀；时间点用 `Instant`，字段以 `At` 结尾（`createdAt`）；时长用 `Duration`。
- 布尔字段和方法：`isXxx`、`hasXxx`、`canXxx`。
- 数据库：表和列用 snake_case；表名用复数。JSON 字段用 snake_case。
- 错误码：`<领域>.<原因>`，全小写，如 `session.not_found`、`turn.lease_lost`。
- 测试类：单元测试 `*Test`，集成测试 `*IT`，真实模型测试 `*LiveIT`。

## 类型与不可变

- DTO、值对象、配置属性一律用 `record`。
- 领域对象默认不可变；需要状态迁移的实体，通过返回新实例或显式的迁移方法实现，迁移方法校验合法性。
- `Optional` 只用作返回值，不用作字段和参数。集合返回空集合，不返回 `null`。
- 多态的封闭类型用 `sealed interface` 加 `record`，并用 `switch` 模式匹配穷举处理。新增子类型时，编译器会指出所有遗漏的分支。

## 错误处理

- 业务错误抛 `MeridianException`，携带 `ErrorCode`；在 HTTP 边界由 `security.web` 映射为 problem+json。
- 只在系统边界校验输入：HTTP 请求、外部 API 返回、模型输出、配置。内部代码信任调用方，不重复防御。
- 异常只记录一次日志，位置在边界处理器。中间层要么处理，要么原样抛出或包装后抛出，不吞异常，不打印后再抛。
- 不为不可能发生的情况加 fallback。

## 日志

- SLF4J，结构化键值，例如 `log.info("轮次已认领 turnId={} tenantId={} epoch={}", ...)`。
- 级别：`ERROR` 需要人处理；`WARN` 可自动恢复但值得关注；`INFO` 关键状态迁移；`DEBUG` 排查细节。
- 禁止记录凭据、API Key、提示词正文、模型输出正文、终端用户的个人信息。
- MDC 键固定为 `trace_id`、`span_id`、`request_id`、`tenant_id`、`session_id`、`turn_id`。`request_id` 由 `security.web` 的请求 ID 过滤器写入（ADR-0020）。

## 配置

- 可调参数放进 `*Properties`，在代码中给出默认值，并在注释中写明默认值的来源。
- 架构设计中规定的数值（租约 30 秒、续租 10 秒、回收周期 5 秒、并发上限等），用常量或配置默认值表达，注释引用章节名，不在业务代码中散落魔法数字。
- 本机路径、凭据、端点只能来自环境变量或本地配置，不能提交到仓库。

## 并发与响应式

- runtime 基于 WebFlux。Reactor 线程上禁止阻塞调用；JDBC、对象存储、沙箱 HTTP 等阻塞操作，一律经 `common.concurrent.Blocking` 调度到虚拟线程。测试中启用 BlockHound 检测违规。
- control-plane 和 knowledge 基于 MVC 加虚拟线程，按普通阻塞代码编写。
- `PlatformContext` 在 MVC 服务中用 `ScopedValue` 绑定，在 runtime 中写入 Reactor Context；跨越两者时显式传递，不用 `ThreadLocal`。

## 数据访问

- 统一使用 Spring `JdbcClient`，SQL 用文本块写在仓储类中，不引入 JPA 和 MyBatis（ADR-0020）。
- 业务仓储继承 `TenantScopedRepository`，构造时声明项目级或租户级作用域；跨租户的后台作业继承 `SystemRepository`，只能放在 `infra.system` 包并标注 `@CrossTenant(reason = "...")`。其他类禁止直接注入 `JdbcClient` 或 `JdbcTemplate`（架构设计「租户隔离」）。
- 迁移文件放在各应用的 `src/main/resources/db/migration/<schema>/`，命名 `V0001__描述.sql`，序号四位递增。已经合入的迁移文件不修改，修正通过新的迁移完成。
- 应用运行时使用只有 DML 权限的数据库角色，DDL 只由 Flyway 使用迁移角色执行。

## API

- OpenAPI 是唯一定义，先改规范，再生成代码，最后写实现。
- `api` 层的处理器实现生成的接口，不在处理器里写业务逻辑。
- 字段描述、错误说明用中文写进规范；字段名用英文 snake_case。

## 依赖管理

- 所有版本只在 `meridian-bom` 声明，子模块的依赖不写 `<version>`。
- 新增依赖前确认：许可证为 Apache-2.0、MIT、BSD、EPL 或同等宽松许可；没有被架构设计列为禁止；不与已有依赖功能重复。重量级依赖（引入新的运行时框架、新的存储）走 ADR。
- 版本选择：取最新稳定版，发布至少两周；记录在 BOM 中，并注释选择理由（如有特殊约束）。

## Python 子项目

适用于 `doc-parser/` 和 `sandbox-runtime/`。

- 用 uv 管理依赖与虚拟环境，锁文件提交。
- 用 Ruff 格式化与检查，用 Pyright 或 mypy 做类型检查，函数签名必须有类型标注。
- 模块和公开函数写中文 docstring。
- 测试用 pytest。

## SQL

- 关键字大写，标识符小写 snake_case。
- 每张表有主键；外键关系在同一 schema 内用外键约束，跨 schema 不建外键。
- 时间列统一 `timestamptz`。
- 需要并发控制的更新，在 SQL 中写清条件（`WHERE lease_epoch = :epoch`），并检查影响行数。

## Git 提交

- 提交信息遵循 Conventional Commits，摘要用中文，scope 用模块短名：`feat(runtime): 实现轮次认领与租约续期`。
- 每个提交是一个可编译的逻辑单元，且受影响模块的单元测试通过；全量检查（`scripts/dev/verify.sh`）在任务开工与收尾时各跑一次。
- 只提交本任务范围内的改动；提交前核对 `git status` 与 `git diff --staged`。
- 不推送。推送由用户决定。
