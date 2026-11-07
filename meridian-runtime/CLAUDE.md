# meridian-runtime

运行时应用（Spring WebFlux，schema `rt`）：会话与线程、事件与 seq、轮次调度与租约、智能体状态的带租约存储；T06 起执行智能体，T07 起 HTTP API 与 SSE。不管理租户、凭据、模型目录（control-plane），不做知识库（knowledge）。

## 命令

- 编译与单测：`./mvnw -pl meridian-runtime -am test`
- 集成测试（Testcontainers：PostgreSQL、Redis）：`./mvnw -pl meridian-runtime -am verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false`；单个再加 `-Dit.test=<类名> -Dfailsafe.failIfNoSpecifiedTests=false`
- 本地启动：`scripts/dev/up.sh` 后运行 `scripts/dev/run-runtime.sh [--server.port=18080]`（`dev` profile，口令从 compose 的 env 文件传入，Ctrl+C 停止）；启动时 Flyway 以 `meridian_migrator` 迁移 `rt`。健康检查 `GET /actuator/health`，探针 `/actuator/health/liveness`、`/readiness`。
- profile：`dev` 连本地 compose（`application-dev.yml`，口令只读环境变量）；`test` 由测试的 `@DynamicPropertySource` 注入连接，节点 ID 固定为 `test-node`。
- 迁移：`src/main/resources/db/migration/rt/`，`V0001__runtime_core.sql` 建出架构设计 runtime 核心表全部（含后续任务的表）；已合入的迁移不改，修正写新迁移。

## 包地图

- `dispatch.app`：`TurnEnqueuer`（计数行、轮次、输入同事务，提交后唤醒）、`TurnClaimer`（三步认领，独立事务）、`LeaseRenewer`（10 秒）、`LeaseReaper`（5 秒）、`TurnTransitions`（置回队列三种原因、结束轮次）、`DispatchWakeups`（`dispatch.wakeup` 通知与 1 秒轮询）、`NodeLeaseRegistry`（`LeaseRegistry` 端口）与 `LeaseHandle`。后台任务在 `config.DispatchConfiguration` 中以 `PeriodicJob` 装配。
- `stream.app.SessionEventWriter`：事件写入的唯一入口（Worker、API、回收器共用）；`DatabaseEventSink` 是 `EventSinkPort`（XADD 由 T06 加入）。`stream.infra.EngineJson`：事件 `data` 与 `turn_inputs.payload` 的编解码。
- `session.infra`：会话、线程的项目级仓储；`session.infra.system.JdbcAgentStateRepository`：`AgentStateRepository` 端口。
- 结果类型都是 sealed：`EnqueueResult`、`ClaimResult`、`TransitionResult`、`AppendResult`；租约条件不满足是结果，不是异常（状态端口按契约抛 `LeaseLostException`）。

## 不变量

- 时间一律取注入的 `Clock`，SQL 绑定 `:now`，不用 `now()`（ADR-0027）；数值来自 `DispatchProperties`，默认值见架构设计「租约」「租约回收」。
- 带租约条件的写入先 `SELECT ... FROM rt.turns ... FOR SHARE` 锁住持有租约的轮次，再写；条件是 `status='running' AND lease_owner = 本节点 AND lease_epoch = epoch`（架构设计「租约与 fencing」，ADR-0027 第 3 条）。
- 锁顺序：先 `rt.turns`，再 `rt.tenant_concurrency`，再 `rt.sessions`。同一事务里既入队又写事件时先入队。
- 同节点重新认领：`LeaseHandle.release()` 之前本节点不再认领该线程（认领 SQL 排除 `heldThreadIds()`，`reserve` 用 `putIfAbsent`）。执行方必须在 `finally` 中 release。
- seq 只由 `SessionEventWriter` 在会话行锁内分配；租约丢失不占用 seq。状态事件在同一事务中推进 `sessions.status`、`threads.status`。
- 置回队列与结束保留 `lease_owner`、`lease_epoch`，恢复认领时失败那次的 epoch 是 `epoch - 1`；释放租户名额不减到负数。
- 本模块的用例与仓储方法都是阻塞的：响应式代码一律经 `Blocking` 调用（`DatabaseEventSink` 是范例），后台任务在 `PeriodicJob` 的虚拟线程上运行；`JdbcAgentStateRepository` 由 AgentScope 在 `boundedElastic` 上同步调用。调度与端口仓储继承 `SystemRepository` 并带 `@CrossTenant`。

## 认领 SQL 的执行计划

`ClaimQueryPlanIT`（2 万个已结束轮次、50 个待认领、20 个执行中，`ANALYZE` 后）：认领为 `Limit → LockRows → Nested Loop`，轮次侧 `Index Scan using turns_queued_idx`，计数行侧对 `tenant_concurrency` 顺序扫描（行数等于租户数）；回收走 `turns_lease_expiry_idx`；状态写入的租约条件走 `turns_lease_owner_idx`。改认领 SQL（`TurnQueueRepository.CLAIM_CANDIDATE_SQL`）后重跑该测试。

## 并发测试的写法

- 节点：`support.TestNode.create(节点 ID, 时钟)` 按生产依赖手工装配一个节点，多个节点共享数据库与同一个 `MutableClock`，模拟多节点；唤醒用计数器代替 Redis。
- 数据：`support.TestSessions` 经仓储建会话与主线程；认领与回收跨租户运行，相关测试在 `@BeforeEach` 调 `RuntimeTestDatabase.truncate()`。
- 时间：推进 `MutableClock` 让租约过期，直接调用 `reaper().reapExpired()`、`renewer().renewAll()`，不 sleep。
- 竞争：线程池加 `CountDownLatch` 同时起跑；确定性的竞争用另一个连接持有行锁，以 `pg_locks` 中未授予的锁为同步点（迁移角色看不到其他角色在 `pg_stat_activity` 中的等待事件）。
- 读库断言用 `support.DbAssertions`（迁移角色）；行映射中的 `timestamptz` 是 `java.sql.Timestamp`，用 `DbAssertions.instant` 转换。
- BlockHound 由 `blockhound-junit-platform` 自动安装到全部测试；放行规则只有 `SecureRandom#nextBytes`（`support.RuntimeBlockHoundIntegration`）。
- 带完整 Spring 上下文的测试加 `@DirtiesContext`：上下文里的回收器用系统时钟，缓存下来会回收其他测试的轮次。

## 易错点

- `ON CONFLICT (thread_id) WHERE status IN (...)` 的条件必须与部分唯一索引一字不差，否则推断不到冲突目标。
- `text[]` 参数传 `String[]`（`JdbcValues.textArray`）；`Instant` 参数在 `SystemRepository` 中用 `JdbcValues.timestamptz`。
- jsonb 会规整键顺序与空白：断言 `data` 原文时按 jsonb 的输出写（键按长度再按字节排序），或解码后比较。
- 认领在 `REQUIRES_NEW` 事务中执行，不要在外层事务里调用；入队、置回、结束、事件写入是 `REQUIRED`，可以组合进一个事务。
- `security.web` 的请求 ID 过滤器在 Netty 事件循环上生成 ULID，会读 `/dev/urandom`；新的 BlockHound 违规先看是不是这条，再决定是否放行。

## 对外接口

- 实现 engine-spi 端口：`LeaseRegistry`（`NodeLeaseRegistry`）、`AgentStateRepository`（`JdbcAgentStateRepository`）、`EventSinkPort`（`DatabaseEventSink`，事件写入部分）。其余端口由 T06 起的任务实现。
- 给 T06：`TurnClaimer.claim()` 得到 `LeaseHandle`（`listen` 接收中断与租约丢失），`TurnTransitions.requeue/finish`，`SessionEventWriter.append`，`TurnQueueRepository.findInput` 加 `EngineJson.readInput`，`DispatchWakeups.signals()`。
