# meridian-common

平台通用基础库：带前缀的 ULID、错误码与平台异常、游标分页、JSON 约定、时钟、`PlatformContext` 及其传递、`Blocking`、出站地址的 SSRF 策略。不含业务语义、HTTP 映射与数据访问。

## 命令

- 编译与单测：`./mvnw -pl meridian-common -am test`
- 单个测试：`./mvnw -pl meridian-common -am test -Dtest=<类名> -Dsurefire.failIfNoSpecifiedTests=false`

## 包地图

- `id`：`Ids.newId(IdPrefix.X)` 生成对外 ID；`Ids.newUlid()` 只给不在「ID 规则」表中的内部记录（outbox、请求 ID）。
- `error`：每个业务领域一个实现 `ErrorCode` 的枚举，码值 `<领域>.<原因>`；枚举的单元测试调用 `ErrorCodes.verify(values())`。`type` 为 `urn:meridian:error:<code>`。
- `page`：`Cursor` 带校验和不带签名，别的列表的游标校验和同样正确，读键用 `key`、`instantKey`、`intKey`（键缺失或类型不对是 400，R2）；查询按 `PageRequest.fetchSize()`（limit + 1）取行，再用 `PageResult.of` 组装。
- `json`：`JsonMappers.strict()` 解析入站请求（未知字段失败），`lenient()` 解析外部响应与已存数据；两者输出相同。
- `time`：生产注入 `Clock.systemUTC()`，测试用 `MutableClock`。
- `context`：`PlatformContexts` 同时提供 `ScopedValue`（`run`、`call`、`current`）与 Reactor Context（`write`、`find(view)`、`currentReactive`）两套读写。
- `concurrent`：`Blocking.call/run` 在虚拟线程上执行阻塞调用，并把 Reactor Context 中的 `PlatformContext` 绑定为 `ScopedValue`。
- `net`（T15a，ADR-0045）：`AddressPolicy.classify` 给地址分类（私有、回环、链路本地、云元数据、保留……），`isBlocked` 是 `web_fetch` 的"只放行公网"，`literal` 解析 IP 字面量的全部写法；`EndpointPolicy` 校验管理员配置的端点（`http(s)`、无用户信息，危险地址始终拒绝，私有与保留网段按配置放行，回环按开关）。
- `error.ReferencedException`：资源仍被引用（409），`ProblemMapper` 输出 `references`。
- `budget`（T16，ADR-0048）：`BudgetLevel`、`BudgetKeys`（已用计数、限额哈希、预扣的 Redis 键与周期）；`audit`：`AuditActor`（审计消息的操作者）。control-plane 与 runtime 共用，键的格式改了两边同时生效。

## 不变量

- 只依赖 JDK、Jackson 3、reactor-core、ulid-creator；reactor 只能出现在 `context` 与 `concurrent`（架构设计「模块依赖规则」，ArchUnit 规则 7）。
- `PlatformContext.tenantId` 必填，其余可为空；Reactor Context 的键是 `PlatformContext.class`（架构设计「身份映射」）。`endUserId`（T10a）由 Worker 从会话填入，工作区命名空间取它；公开 API 的幂等作用域用 `idempotencyPrincipal()`（控制台用户加项目，ADR-0054）；外部只用 `builder()` 构造，记录组件可以增加。
- 错误码一经发布只能新增，不能改名；`MeridianException.detail` 原样返回调用方，不能含凭据与内部信息。

## 易错点

- 不在 `PlatformContexts.call/run` 作用域内调用 `current()` 会抛 `IllegalStateException`；runtime 中经 `Blocking` 执行的回调才有作用域。
- `ScopedValue` 的绑定不会自动跨线程：自己开线程或线程池时显式传递上下文。
- `Cursor.decode`、`PageRequest.of`、`Ids.parse` 对外部输入抛 `ValidationException`（400），不要再包一层。

## 对外接口

被所有模块依赖；`ValidationException`、`NotFoundException` 等在 HTTP 边界由 `meridian-security` 的 `ProblemMapper` 映射为 problem+json。
