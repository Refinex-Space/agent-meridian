# meridian-runtime

运行时应用（Spring WebFlux，schema `rt`）：公开 API（会话、事件、线程、待处理动作）与 SSE、会话与线程、事件与 seq、轮次调度与租约、带租约的智能体状态存储、Worker 执行智能体与崩溃恢复、消息投递与中断、待处理动作与恢复轮、权限合成、收件箱、工具账本、模型路由（限流、预算、计量）。不管理租户、凭据、模型目录（control-plane），不做知识库（knowledge）。

## 命令

- 编译与单测：`./mvnw -pl meridian-runtime -am test`
- 集成测试（Testcontainers：PostgreSQL、Redis、MinIO）：`./mvnw -pl meridian-runtime -am verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false`；单个再加 `-Dit.test=<类名> -Dfailsafe.failIfNoSpecifiedTests=false`
- IDE 后台编译会把坏的 class 写进本模块的 `target/`（报 `Unresolved compilation problems` 或"无法访问 Interaction"），跑测试前先 `./mvnw -q clean -pl meridian-runtime`。
- 本地启动：`scripts/dev/up.sh` 后运行 `scripts/dev/run-runtime.sh [--server.port=18080]`（`dev` profile，连 compose 的 PostgreSQL、Redis、MinIO，口令从 env 文件传入）。健康检查 `GET /actuator/health`，探针 `/actuator/health/liveness`、`/readiness`。
- profile：`dev` 连本地 compose；`test` 由测试的 `@DynamicPropertySource` 注入连接（含 `SpringContainerProperties.minio`，对象存储是必需配置），节点 ID 固定为 `test-node`。
- 迁移：`src/main/resources/db/migration/rt/`（`V0001` 核心表、`V0002` 状态的 `content_turn_id`、`V0003` 会话的 `deleted_at`、`V0004` 动作的结论与权限规则表）；已合入的迁移不改，修正写新迁移。
- 公开 API 的接口从 `meridian-api` 的规范生成到 `target/generated-sources/openapi`（`runtime.openapi`），改规范后先编译；契约测试：`-Dit.test='*ContractIT,SessionApiIT,EventStream*IT'`。本地调用用 `.env` 的 `MERIDIAN_DEV_API_KEY`（`dev` profile 的 `DevApiKeyResolver`）。
- 开发配置：`dev-config/agents/*.yaml`（仓库根目录，`kind: agent_version | environment`），`meridian.runtime.dev-agent-config.directory` 可改；T15b 起智能体配置由 control-plane 下发。供应商与模型目录 `dev-config/providers/*.yaml`（`kind: provider_account`，内含 `models`，凭据只写环境变量名 `secret_ref`），预算限额 `dev-config/budgets/*.yaml`（`kind: budget`，`scope_id: "*"` 必须加引号，不存在时不限），前缀 `meridian.runtime.model-routing`；T15a、T16 起由 control-plane 下发。
- 真实模型测试：`./mvnw -Plive -pl meridian-runtime -am verify`（只跑 `*LiveIT`，需要 Docker 与 `DASHSCOPE_API_KEY`，模型取 `MERIDIAN_LIVE_CHAT_MODEL`，默认 `qwen-plus`；没有密钥时跳过）。

## 包地图

- `session.app.TurnWorker` 认领轮次，`TurnExecution.run()` 是执行一轮的唯一入口：`TurnPreparer`（读输入、恢复判定、写 `status_running`）→ `AgentEngine.runTurn` → 每个事件带租约写库 → 终态交给 `TurnFinisher`（结束轮次、计数、合并收件箱成下一轮、待处理动作、outbox、置回队列）→ `finally` 释放租约条目。`TurnWorkerLifecycle` 是后台循环与优雅下线（`SmartLifecycle`）。
- 恢复判定是纯逻辑：`session.domain.RecoveryPlanner.decide(RecoverySnapshot)`，单测 `RecoveryPlannerTest`；停止原因到轮次状态的映射在 `TurnEnding`。
- `dispatch.app`：`TurnEnqueuer`、`TurnClaimer`（`input(turn)` 读轮次输入）、`LeaseRenewer`、`LeaseReaper`、`TurnTransitions`、`DispatchWakeups`、`NodeLeaseRegistry`、`QueueDepthGauge`。后台任务在 `config.DispatchConfiguration` 中以 `PeriodicJob` 装配。
- `stream.app.SessionEventWriter`：事件写入的唯一入口，提交后 XADD 到 `ev:{session_id}`（`EventStreamPublisher`）；`DatabaseEventSink` 是 `EventSinkPort`；`ObjectStoreBlobStore` 是 `BlobStorePort`（键 `rt/{tenant}/{session}/blobs/…`）。`stream.infra.EngineJson`：事件、轮次输入、自定义工具与开发配置的 JSON 编解码。
- `delivery.app.DatabaseInbox`：`InboxPort`（steer 带租约消费，同事务写 `session.message_consumed`）、`takePending`（合并成下一轮）、`redeliverSteer`。`tooling.app.DatabaseToolLedger`：`ToolLedgerPort`。
- `approval`（T08，ADR-0031）：`domain.PermissionSynthesizer`（五层合成、两种改写、子智能体策略，纯函数）与 `PlatformSafetyRules`；`app.PermissionPolicies` 每轮读取规则并合成（`TurnPreparer` 调用）；`app.PolicyStorePort` 是租户与项目策略的内部端口，开发实现 `infra.DatabasePolicyStore`（`rt.permission_rules`）；`app.PendingActionResolver` 记录结论（回应、中断、超时）、"记住此决定"、按 `domain.ResumptionPlanner` 生成恢复轮；`app.PendingActionTimeouts` 超时扫描；`app.ApprovalAudit` 写 `permission.decided` 与 `pending_action.concluded`，Worker 的事件写入也经它。
- 中断：`delivery.app.EventIntake` 按主线程的未结束轮次（`TurnTransitions.lockActiveOnThread`）处理；`dispatch.app.NodeChannel` 在提交后发布 `node:{lease_owner}`，`NodeChannelListener` 收到后 `LeaseRenewer.renewThread`。
- `session.infra.system`：`JdbcAgentStateRepository`（`AgentStateRepository`，另有 `contentEpochOfTurn`）、`ThreadLockRepository`（线程咨询锁）。
- `modelrouting`（T09a，ADR-0032）：`app.RoutingModelGateway` 是 `ModelGateway`（路由与凭据、限流等待与预检、四级预扣、结算先写 outbox `usage` 再修正 Redis）；`app.ModelCatalogPort`、`app.BudgetLimitPort` 是内部端口，开发实现 `infra.FileModelCatalog`、`infra.FileBudgetLimits`；`infra.RedisRateLimiter`、`infra.RedisBudgetLedger` 执行 Lua（`src/main/resources/cn/refinex/meridian/runtime/modelrouting/infra/*.lua`）；`app.ModelMetrics` 三个指标；`domain.ModelUsageMessage` 是用量消息格式。
- `config`：`ModelRoutingConfiguration`（目录、限额、`EnvironmentSecretResolver` 只读 `*_API_KEY` 环境变量）、`ModelFactoryAutoConfiguration`（`RoutedModelFactory`，排在 `EngineAutoConfiguration` 之前，测试自己声明 `ModelFactory` 时不创建）；`WorkerConfiguration`（执行配置、`AgentConfigProvider` 的文件实现 `FileAgentConfigProvider`、`AgentConfigPort`、`BlobStorePort`、保留工具名 `ReservedToolNames`、待处理动作超时扫描任务）；`DispatchConfiguration` 另装配节点频道的订阅；`EngineAutoConfiguration` 是自动配置，模型工厂、工具、文件系统、技能仓库四个提供者都存在时才装配 `AgentScopeEngine` 与 Worker（T09a、T10a 之前生产环境不认领轮次）。
- 公开 API：`session.api`（`SessionsApiController`、`ThreadsApiController`）、`stream.api.EventsApiController`、`approval.api.PendingActionsApiController` 实现生成的接口，只做作用域检查、decode → call → map；POST 经 `web.IdempotentRequests`。用例：`session.app.SessionService`（创建固化版本、归档与删除取消轮次并作废动作）、`delivery.app.EventIntake`（发送事件，组合规则在 `delivery.domain.EventAdmission`）、`stream.app.EventQueries`、`approval.app.PendingActionQueries`。
- SSE：`stream.app.SessionStreamHub` 是本节点读取 `ev:{session_id}` 的唯一入口（一个不阻塞 `XREAD` 轮询全部被订阅的会话）；`EventStreamService` 按五步算法处理每个连接，范围由 `stream.domain.StreamFilter` 决定（ADR-0030）。
- 结果类型都是 sealed：`EnqueueResult`、`ClaimResult`、`TransitionResult`、`AppendResult`、`TurnResult`、`RecoveryDecision`；租约条件不满足是结果，不是异常（端口按契约抛 `LeaseLostException`）。

## 不变量

- 时间一律取注入的 `Clock`，SQL 绑定 `:now`（ADR-0027）；数值来自 `DispatchProperties`、`WorkerProperties`。
- 带租约条件的写入先 `SELECT ... FROM rt.turns ... FOR SHARE` 锁住持有租约的轮次再写（「租约与 fencing」）。锁顺序：先 `rt.turns`，再 `rt.tenant_concurrency`，再 `rt.sessions`；同一事务里既入队又写事件时先入队。
- 失去租约（续租 `REVOKED`、写入被拒、内核抛 `LeaseLostException`）只取消订阅，禁止 `interrupt`；放弃的执行不写任何数据。
- 恢复认领按 `epoch > 1` 判断（下线续跑不加 `recovery_attempt`）；恢复方式按"本轮的内容写入"（`content_turn_id`）判定，不只比 `content_epoch`；`recovery_attempt` 达 2 时不再执行（ADR-0028 第 1、2 条）。
- 同节点重新认领：`LeaseHandle.release()` 之前本节点不再认领该线程；`TurnExecution` 在 `doFinally` 中释放。
- seq 只由 `SessionEventWriter` 在会话行锁内分配；状态事件在同一事务中推进会话与线程状态。XADD 失败不回滚，只记 `meridian.event.publish.failures`。
- Redis 命令在调用线程上按顺序发出，不在前一条命令完成的回调（Netty 事件循环）上发下一条（`Mono.zip`、`flatMapSequential`）。
- 模型路由的 Redis 用阻塞的 `StringRedisTemplate` 经 `Blocking` 执行，不在 Netty 事件循环上发命令；限流与预算的时间取注入的 `Clock`。预算计数以 token 计，键带租户哈希标签 `budget:{租户}:…`，令牌桶键 `ratelimit:{供应商账号}:{模型}`。
- 本模块的用例与仓储都是阻塞的：响应式代码一律经 `Blocking`；调度与端口实现的跨租户仓储继承 `SystemRepository` 并带 `@CrossTenant`，项目级仓储从 `PlatformContext` 取租户与项目。
- 指标（`session.app.TurnMetrics`、`EventStreamService`）用 Micrometer 点分隔名，导出到 Prometheus 时变成「可观测性」表中的名字。
- `/v1/` 下的请求都经 `ApiKeyWebFilter` 认证，Reactor Context 中有 `PlatformContext`；处理器先 `ApiKeyAuthorization.require` 再调用用例。会话不属于当前项目与不存在相同（404）：跨租户的仓储（事件、SSE）之前先经 `SessionService.get` 确认归属。
- 结束轮次、发送事件、归档、删除的事务都先取线程咨询锁（`ThreadLockRepository`），锁住之后再读会话状态；漏掉时消息会留在收件箱没有轮次处理（ADR-0028 第 8 条、ADR-0030）。先锁了行再要咨询锁的后台作业（超时扫描）只能 `tryLock`，否则与上面的事务死锁。
- 恢复轮（回应、超时、中断、无人值守自动拒绝）都经 `PendingActionResolver` 在线程咨询锁下入队：等待回应的轮次先结束为 `completed`，再入队，部分唯一索引才放得下。带中断请求入队的轮次，`TurnContext.interruptRequested` 为真，内核在第一次推理前停止。
- 每轮合成的权限都带平台安全规则，AgentScope 不再走"平凡权限上下文一律放行"的路径：`DEFAULT` 模式下没有命中 allow 的工具调用都要确认，测试智能体要显式放行它的工具（`WorkerFixture.LEDGER_TOOLS_ALLOWED`）；会话声明的自定义工具在会话层自动放行。

## 故障注入测试的写法

- 多节点：`config.WorkerFixture` 共享时钟、`ScriptedModel` 脚本（按调用顺序跨节点消费）、智能体配置（`TestAgents`）与记账工具；`fixture.node("node-a")` 组装完整节点（`WorkerNode`：`TestNode` + 收件箱、账本、对象存储、真实装配的内核、Worker）。引用 `meridian-engine-agentscope` 的测试放在 `config` 包。
- 卡住执行：`LedgerTool` 记账并在执行中途等闸门（`fixture.lookupCalls.close()`/`open()`、`awaitEntered()`）；放行要在 `finally` 中。
- 强杀：`execution.cancel(...)`（不收尾），时钟推进 31 秒，`WorkerFixture.reap(另一节点)` 重试回收直到成功（正在被写入的轮次行会被 `SKIP LOCKED` 跳过）。工具在接管之后再放行，它的结束记录写不进账本。
- 中断：经 `fixture.post(node, session, new UserEvent.Interrupt())` 发送（与 API 相同的 `EventIntake`），再调 `renewer().renewAll()` 模拟续租周期，或 `node.channelListener().start()` 走节点频道。下线：`node.lifecycle().start()`/`stop()`。动用下线的类在 `@AfterEach` 调 `GracefulShutdownManager.getInstance().resetForTesting()`（JVM 单例）。
- 模型层测试：`config.ModelRoutingFixture` 组装真实的 `RoutingModelGateway`（临时目录的目录与限额、Testcontainers 的 Redis 与 outbox），`routedModel(...)` 走平台 HTTP 传输或指定的底层客户端工厂，`live(dir)` 连 DashScope；Worker 层用 `new WorkerFixture(脚本, 配置, routedModel)` 让智能体装配 `RoutedModel`（底层供应商是脚本，`fixture.model` 仍可读请求）；模拟供应商用 `mockwebserver3`（`RoutedModelProviderIT`）。
- T08 的流程测试经 `fixture.post` 发送消息与回应；`WorkerFixture::lookupOnlyAllowed` 让写入工具默认 ask 来制造等待回应；`fixture.extraTools` 加入 `PathRuleTool` 等不记账的工具；`fixture.timeouts(node)`、`fixture.sessions(node)` 是超时扫描与会话用例；只放行最早一次关闭的闸门用 `Calls.openOldest()`。
- 启动了 `TurnWorkerLifecycle` 的测试若中途失败没有 `stop()`，它会继续认领后面测试的轮次，造成大量连锁失败：先看第一个失败。
- 旧执行的写入被拒：在内核层直接 `engine().runTurn(...)`（还没察觉租约丢失），比较前后的 `ThreadSnapshot`，见 `WorkerLeaseFencingIT`。
- 单节点的调度测试用 `support.TestNode`；读库断言用 `support.DbAssertions`（迁移角色，`row`、`rows`、`list`）；`timestamptz` 用 `DbAssertions.instant` 转换。Redis 断言用 `RuntimeTestRedis.readAll`（XADD 是提交后异步发出的，用 `WorkerFixture.awaitStream` 等待）。
- BlockHound 由 `blockhound-junit-platform` 自动安装；放行规则见 `support.RuntimeBlockHoundIntegration`（`SecureRandom#nextBytes`、Lettuce `SharedLock`、Hibernate Validator 第一次加载消息资源包）。带完整 Spring 上下文的测试加 `@DirtiesContext`。

## 易错点

- 生成的 DTO 枚举在查询参数中按规范取值转换（`web.ApiEnumConverterFactory`），在 JSON 中由 `@JsonValue` 处理；生成的类名与领域类同名（`Session`、`SessionThread`），映射类里用全限定名区分。
- 公开 API 的集成测试继承 `support.ApiTestBase`（共用一个容器，不加 `@DirtiesContext`），经 `ApiTestClient`、`SseTestClient` 调用，每次交互都按规范校验；需要独占 Redis 的测试（`EventStreamRedisOutageIT`）自起容器。写事件模拟 Worker 用 `support.EventFeeder`（含"提交后不 XADD"）。
- `ON CONFLICT (thread_id) WHERE status IN (...)` 的条件必须与部分唯一索引一字不差，否则推断不到冲突目标。
- `text[]` 参数传 `String[]`（`JdbcValues.textArray`）；`Instant` 参数在 `SystemRepository` 中用 `JdbcValues.timestamptz`。jsonb 写入经 `JdbcValues.jsonb`，NUL 会被替换为 U+FFFD。
- jsonb 会规整键顺序与空白：断言 `data` 原文时按 jsonb 的输出写（`": "` 带空格），或解码后比较。
- 认领在 `REQUIRES_NEW` 事务中执行；入队、置回、结束、事件写入、收件箱、账本是 `REQUIRED`，可以组合进一个事务。
- 内核事件的写入与 AgentScope 的执行是并行的：工具开始执行时 Worker 可能还在写上一个事件，测试要等事件写完再接管。
- 批次级 checkpoint 开启时，强杀发生在第一批工具之后会走 `RecoveryOnly`；要测"重放完整输入"，在第一批工具执行中途强杀，或关闭 checkpoint（`WorkerFixture.withoutCheckpoint()`）。

## 对外接口

- 实现 engine-spi 端口：`LeaseRegistry`、`AgentStateRepository`、`EventSinkPort`、`InboxPort`、`ToolLedgerPort`、`BlobStorePort`、`AgentConfigPort`；经 `AgentEngine` 使用内核。
- 实现 engine-spi 端口 `ModelGateway`（`modelrouting`）与 `SecretResolver`（`config`，开发实现）。给 T09b：`ModelInvoker` 复用 `RoutedModel`，直通接口的上下文没有会话，预算只计租户、项目、API Key。给 T15a：替换 `ModelCatalogPort`、`SecretResolver` 的开发实现（目录不区分项目）。给 T16：用量消息格式见 `ModelUsageMessage`，Redis 计数键见 `RedisBudgetLedger`，限额来源换掉 `BudgetLimitPort` 的文件实现。
- 公开 API：规范 `meridian-api/src/main/resources/openapi/meridian-v1.yaml` 中的会话、事件、线程、待处理动作与两个事件流。给 T10a：平台工具经 `ToolLedgerPort` 记账，结束时写入结果引用，重试时用 `ToolRetryContext` 的原幂等键。给 T13：`TurnContext.subagentPolicies` 已按子线程改写合成。给 T15b：`PolicyStorePort` 换成 control-plane 实现。给 T16：审计消息 `turn.finished`、`permission.decided`、`pending_action.concluded`。
