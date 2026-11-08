# meridian-engine-spi

平台与智能体内核之间的全部契约：`AgentEngine`、一轮的上下文与输入、平台事件模型、已解析的配置、runtime 实现的端口、沙箱与模型驱动接口。只有接口与值对象，不含实现。

## 命令

- 编译与单测：`./mvnw -pl meridian-engine-spi -am test`
- 单个测试：`./mvnw -pl meridian-engine-spi -am test -Dtest=<类名> -Dsurefire.failIfNoSpecifiedTests=false`

## 包地图

- `engine`：`AgentEngine`（`runTurn`、`interrupt`、`shutdown`，T08 起另有 `retryToolCall` 与 `reservedToolNames`）、`TurnContext`（只带配置 ID 与修订号，配置经 `AgentConfigPort` 取；`interruptRequested` 让内核在第一次推理前停止）、`ThreadRef`、`RecoveryOrigin`（失败的 epoch、第几次恢复、`REPLAY_INPUT`/`RESUME_SAVED_STATE`）、`ToolRetry`（恢复确认的重试）。
- `engine.input`：`TurnInput` 四种输入（嵌套 record），`kind()` 给出 `rt.turns.kind`；`MessageContent`（文本、图片、文件引用）被用户消息、自定义工具结果、`agent.message` 共用。
- `event`：`EngineEvent` 按域分为 `UserEvent`、`AgentOutputEvent`、`SessionEvent`、`SpanEvent`、`StreamEvent`，具体记录嵌套在域接口里；`EventType` 登记每种类型的取值、域与记录类，`type()` 由记录类反查。
- `spec`：`ResolvedAgentSpec`（含 `SubagentSpec`、`McpServerSpec`、`SkillRef`、`KnowledgeSpec`、`CompactionSpec`；`grants(tool)` 判断工具集）、`SkillDefinition`（技能内容，T10a）、`EnvironmentSpec`、`PermissionPolicy`/`PermissionRule`、`CustomToolDeclaration`、`PlatformToolNames`。
- `port`：架构设计「端口」表的全部端口，加 `AgentConfigPort`（ADR-0024，T10a 起另有 `skill`，T10b 起另有 `tenantPrompt`）；`WebFetchPort` 被拒绝时以 `ForbiddenException` 失败、其他失败的消息是给模型看的英文；`ModelGateway` 另有 `describe`、`precheck` 与计量记录 `ModelCallRecord`（ADR-0032）；`CallPurpose` 另有 `EMBEDDING`、`RERANK`（ADR-0033）；`SnapshotStorePort`（T11，同步，只接受 `rt/snapshots/` 之下的键，ADR-0036）；`ToolLedgerPort.recordSandboxCommand`（T12a，沙箱命令审计，不受租约约束，ADR-0037）。`event.ContextComposition` 是 `span.model_request_start` 的上下文构成。端口专用的值类型嵌套在端口接口里（如 `LeaseRegistry.Lease`、`AgentStateRepository.StateFence`）。
- `driver`：`SandboxDriver`（T12a 另有 `syncSkills`、`listOutputs`、`readOutput`，目录常量 `SKILLS_DIR`、`OUTPUTS_DIR`）、`SandboxHandle`、`ModelInvoker`（OpenAI 兼容消息模型）。
- `error`：决定一轮如何结束的异常与 `ModelErrorCode`、`ToolErrorCode`（T10b：`McpUnavailableException`，装配时连不上 MCP 服务，可重试，ADR-0035）：`BudgetExceededException`（`budget_exceeded`）、`RetryableTurnException`（可重试错误的基类，`RateLimitTimeoutException`、`ProviderUnavailableException` 继承它）（ADR-0026）、`ProviderRejectedException`（终止）、`CompactionFailedException`（原因链决定本轮如何结束，ADR-0032）、`SandboxUnavailableException`（`SandboxErrorCode.SANDBOX_UNAVAILABLE`，可重试；沙箱已不存在用 `NotFoundException`，ADR-0036）。

## 不变量

- 签名只出现平台类型，不依赖 AgentScope 与 Spring（ArchUnit 规则 12）；只依赖 common 与 reactor-core。
- `EventType` 与架构设计「事件」清单逐一对应，`EventTypeCatalogTest` 直接解析 `docs/architecture/architecture.md` 比对；新增事件类型要同时改文档、`EventType` 与域接口。
- 被 AgentScope 同步接口调用的端口（`AgentStateRepository`、`WorkspaceStorePort`、`LeaseRegistry`、`SnapshotStorePort`）是同步方法，其余返回 `Mono`/`Flux`；租户、项目、会话、epoch 从 `PlatformContext` 取（架构设计「端口」）。
- `McpServerSpec.sideEffect` 缺省为 `NON_IDEMPOTENT`（ADR-0035）。
- 配置记录在构造时校验取值范围：`maxIters` ∈ [1, 200]、子智能体超时 ≤ 600 秒、子智能体工具是父智能体工具的子集、`sandbox` 档必须有规格。

## 易错点

- 工具入参、JSON Schema 可能含 null 值，复制时用 `Collections.unmodifiableMap(new LinkedHashMap<>(m))`，不用 `Map.copyOf`。
- `PendingAction.expiresAt` 由内核留空、runtime 写入待处理动作时用 `withExpiresAt` 补上；动作 ID 由产生方生成。
- `InboxPort.consumeSteer` 的实现要在同一事务中写 `session.message_consumed`（ADR-0031）；内存实现 `InMemoryInbox` 不写事件。
- `SessionEvent.Error` 的名字会遮蔽 `java.lang.Error`，在 `SessionEvent` 内部引用 JDK 的 `Error` 要写全名。
- `session.status_rescheduled` 的原因有三种：`lease_expired`、`node_shutdown`（`recovery_attempt` 不变）、`retryable_error`（前面一定有 `session.error(retryable)`）。内核遇到 `LeaseLostException` 不输出事件，以该异常结束 `runTurn` 的流（ADR-0026）。

## 对外接口

- 由 `meridian-engine-agentscope` 实现：`AgentEngine`（T04）、`SandboxDriver`（T11、T12a）、`ModelInvoker`（T09b）。
- 由 runtime 实现：`port` 包全部端口（T05 起）；内存实现在 `meridian-engine-agentscope-test` 的 `testkit.port`。
