# meridian-engine-agentscope

内核适配层的 AgentScope 2.0.4 实现，唯一依赖 AgentScope 的生产模块：智能体装配与实例缓存、执行一轮、事件映射、带租约校验的状态存储、平台中间件。

## 命令

- 编译与单测：`./mvnw -pl meridian-engine-agentscope -am test`
- 装配、映射与场景测试在 `meridian-engine-agentscope-test`（它依赖本模块，测试放这里会形成循环）：`./mvnw -pl meridian-engine-agentscope-test -am test`
- 读 2.0.4 源码：`./mvnw -pl meridian-engine-agentscope -am compile dependency:sources -DincludeGroupIds=io.agentscope`，再 `unzip -p ~/.m2/repository/io/agentscope/<构件>/2.0.4/<构件>-2.0.4-sources.jar <类路径>.java`

## 包地图

- `turn.AgentScopeEngine`：`AgentEngine` 的实现，执行一轮的唯一入口。`runTurn` 取配置、租用实例、`begin`（身份映射、`clearStateCache`、带标记的权限安装）、`streamEvents`、`TurnEventMapper`，终态前归还资源；`TurnInputTranslator`、`PermissionTranslator` 做输入与权限翻译。
- `events.TurnEventMapper`：事件映射与增量拼装，`complete()` 给出唯一终态，静态的 `fail(Throwable)` 把错误翻译为 `session.error` 加终态；`AgentEventDisposition`（31 个事件类型）与 `StopReasonMapping`（`GenerateReason`）是穷举的 switch。
- `state.FencedAgentStateStore`：`AgentStateStore` 的实现，写入经 `LeaseRegistry` 取租约、经 `AgentStateRepository` 端口在同一事务中校验。
- `middleware`：`PlatformMiddlewareProvider` 交给装配器的平台中间件（参数：租约、收件箱、是否开启批次 checkpoint；子智能体不含 steer）；`PlatformStopRequest` 是平台中间件请求停止并记录原因的入口；`RecoveryNoteMiddleware`、`BatchCheckpointMiddleware`、`InterruptCheckpointMiddleware` 从 `RuntimeContext` 读取引擎放入的 `RecoveryNoteSlot`、`BatchCheckpoint`、`PendingInterrupt`；`SteerMiddleware` 经 `InboxPort` 取 steer（T08，ADR-0031）。
- `turn.AgentScopeEngine` 另有 `retryToolCall`（恢复确认的重试，直接调 Toolkit 中的工具，原幂等键放在 `ToolRetryContext`）与 `reservedToolNames`。
- `assembly.AgentAssembler`：`HarnessAgent.Builder` 的全部配置都在 `configure`，按「引擎装配」表逐段对应；`AgentInstanceCache` 对外 `acquire`/`acquireSubagent`/`release`；`AssembledAgent.reservedToolNames()` 供自定义工具校验。

## 中间件顺序（P13，T04 测得）

`order()` 数值大的在外层；`MiddlewareChainTest` 断言链路与 span 父子关系。装配后的实际链路（外层在前）：

`GracefulShutdownMiddleware`（AgentScope 构造时固定放在最外层）→ `PlatformContextMiddleware`(300) → `PlatformTracingMiddleware`(200，委托 `OtelTracingMiddleware`) → `LeaseGuardMiddleware`(100) → `RecoveryNoteMiddleware`(90) → `InterruptCheckpointMiddleware`(88) → `SteerMiddleware`(85) → `BatchCheckpointMiddleware`(80) → `ToolSpanMiddleware`(50) → harness 自带：`WorkspaceContextMiddleware`、`AtPathExpansionMiddleware`、`CompactionMiddleware`、`ToolResultEvictionMiddleware`、`InboxMiddleware`、`HarnessSkillMiddleware`（都是 1，按注册顺序）。

span 层级：`turn` → `invoke_agent` → `chat {model}` 与 `execute_tool`（批次）→ `execute_tool {name}`（每个调用）。后续中间件选 `order()` 时以此为准：要在压缩之前生效的（T09a 外层守卫）大于 1，要夹在压缩之内的小于 1；在推理前往上下文注入消息的必须大于 80，注入的内容才进入该次 checkpoint（`SteerMiddleware` 为 85）。

## 不变量

- 装配必须满足「引擎装配」表：`maxRetries(1)`、不设 `fallbackModel`、`enablePendingToolRecovery(true)`、关闭全部内置工具与 transcript、`NoopMessageBus` 加 `NoopAsyncToolRegistry`、`enableAgentTracingLog(false)`；`AgentAssemblerTest` 逐项断言，改装配先改表。
- 缓存键 `(agent_version_id, environment_id, environment_revision[, 子智能体名])`，容量 500，空闲 30 分钟；淘汰只退役，引用归零才关闭（ADR-0024）。`release` 由 `AgentScopeEngine` 在终态之前调用，每个凭据一次。
- 每轮恰好一个终态：正常结束按 `GenerateReason`；错误先 `session.error` 后终态；`AgentShuttingDownException` 只发 `rescheduled(node_shutdown)`；`RetryableTurnException` 发 `error(retryable)` 加 `rescheduled(retryable_error)`；`LeaseLostException` 不发任何事件、原样结束；取消订阅不发事件、不写状态（ADR-0026）。
- 每轮开始前与结束时（含异常、取消）都 `clearStateCache(userId, threadId)`；权限安装期间 `LeaseRegistry.markPermissionInstall` 为真，状态存储据此不更新 `content_epoch`（「租约与 fencing」）。
- 同一节点同一线程同时只有一次执行，`AgentScopeEngine` 登记执行，重复执行失败。
- 阻塞步骤（装配、权限安装时的状态读写）经 `Blocking` 在虚拟线程上执行，AgentScope 的调用也在该线程上订阅，`activateSlotForContext` 的状态读取因此不在 Reactor 线程上。

## 易错点

- 2.0.4 在 `onActing` 的事件流结束之后才把工具结果写进 `AgentState`（`notifyPostActingHook`）：批次 checkpoint 放在下一次推理之前保存，在 `onActing` 结束时保存会得到没有 tool_result 的状态（P3）。
- 推理输入是 `PreReasoningEvent` 复制出来的列表：往上下文注入消息要同时加进 `ReasoningInput.messages()` 与 `ctx.getAgentState().contextMutable()`，否则本次模型调用看不到或状态里存不下。
- 工具的 `Mono` 出错（例如账本写入被拒）时，2.0.4 的 `ToolExecutor` 把它转成错误结果，调用继续；不会让整轮失败。

- `maxRetries` 是含首次在内的总尝试次数，0 会抛异常（ADR-0023）。`RoutedModel` 调用底层模型时不能放大它。
- 只设 `messageBus` 不够：不显式设置 `asyncToolRegistry` 时，harness 用工作区实现，`InboxMiddleware` 每次推理前都会读工作区。
- `Toolkit.copy()` 不转移 MCP 客户端所有权：需要关闭的资源放进 `ToolBundle.resources`。
- `disableDynamicSkills()` 让技能清单在构建时冻结；技能仓库必须按装配时的智能体版本解析。
- `CompositeFilesystem` 会剥离目录路由前缀，单文件路由不剥离；每个目录前缀要有自己的后端视图。
- 不设 `workspace(...)` 时本机工作区根是 `${user.dir}/.agentscope/workspace`（登记事项 D05，T10a 处理）。
- 2.0.4 从不赋值 `GenerateReason.STRUCTURED_OUTPUT`，结构化输出完成时为 `MODEL_STOP`。
- 2.0.4 的块 ID 每次模型调用都从 `text` 重新编号，平台块 ID 是 `{replyId}:{blockId}`；并行工具调用交错时同一 ID 会多次 `TOOL_CALL_START/END`，入参在 `MODEL_CALL_END` 才完整（ADR-0026）。
- 一批工具的 `TOOL_RESULT_END` 在整批执行完后才逐个发出；`AgentScopeEngine` 之外不要据此计时单个工具。
- 审批批准时 `ConfirmResult.toolCall` 必须带 `content`（入参 JSON 原文）：`ToolExecutor` 按它做 schema 校验，只给 `input` 会报"argument content is null"。
- `interrupt(userId, sessionId)` 在调用被受理（进入 `runningCalls`）之前是空操作，返回值也拿不到；引擎自己登记执行并在受理后补发。
- `GracefulShutdownManager` 是 JVM 单例，`performGracefulShutdown` 之后拒绝所有新调用；测试结束必须 `resetForTesting()`。`AgentScopeEngine` 构造时把它的超时设为 45 秒。
- `OtelTracingMiddleware` 的 span 在它自己的方法里创建，补属性要在内层从 Reactor Context 取 span（`PlatformTracingMiddleware`）；`ContextPropagationOperator` 来自 AgentScope 带来的 `opentelemetry-reactor-3.1`。
- 自定义工具挂起时 2.0.4 发出 `RUNNING` 的 `TOOL_RESULT_END`，映射器跳过它；`agent.custom_tool_use` 以 `REQUIRE_EXTERNAL_EXECUTION` 中的调用为准。
- 权限（2.0.4 已核实，P15）：显式 ask 规则在 `DONT_ASK` 下仍返回 ask，只有最后一步的默认 ask 变成 deny，所以无人审批的执行要把 ask 改写为 deny；`ConfirmResult.rules` 只加进本次调用缓存的 `PermissionEngine`，不写回状态，"记住此决定"由平台保存。
- 权限上下文"平凡"（`DEFAULT`、没有规则、没有工作目录）时 2.0.4 不走 `PermissionEngine`、一律放行；平台每轮注入安全规则，上下文永远不平凡，`SchemaOnlyTool`（自定义工具）也是 `ToolBase`，同样受规则约束。
- 一批工具中只要有一个 ask，整批都不执行（含已放行与自定义工具），确认之后整批一起执行；全部被拒绝（`ALL_TOOLS_DENIED`）时默认继续推理。
- 在 `onReasoning` 中以 `InterruptedException` 结束等同于用户中断（`handleInterrupt`：补齐悬空调用、保存状态、`INTERRUPTED`）；不调下游只发 `RequestStopEvent` 时没有模型输出，推理返回空，不能这样停。
- 工具直接返回的 `ToolResultBlock` 状态默认为 `RUNNING`（由 `ToolExecutor` 定稿），直接调用工具时不能用 `SUCCESS` 判断成功。

## 对外接口

- 实现 `meridian-engine-spi` 的 `AgentEngine`（`turn.AgentScopeEngine`）；依赖端口 `AgentConfigPort`、`LeaseRegistry`、`AgentStateRepository`、`BlobStorePort`、`InboxPort`。
- runtime 只能在 `config` 包引用本模块的装配入口：`AgentAssembler`、`AgentInstanceCache`、`AgentScopeEngine`、`FencedAgentStateStore`、`PlatformMiddlewareProvider` 与提供者接口。
- 生产实现的提供者：模型工厂 T09a，中间件 T04 起（T06、T08 已加入，T09a、T10a、T19 继续加入），工具 T10a、T10b、T12a、T13，文件系统 T10a、T12a，技能仓库 T10a。
