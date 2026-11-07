# T04 单轮执行适配与事件映射

| 项 | 内容 |
|---|---|
| 阶段 | 1 M0 内核与运行时核心 |
| 依赖 | T03 |
| 推荐配置 | Opus 5.5，effort xhigh，新会话 |
| 规模 | 大 |
| 关联验证项 | P13（部分） |

启动方式：在仓库根目录开新会话，输入 `/task T04`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；AgentScope 只以 2.0.4 为准（读法见 `docs/plan/references.md`）。
- 只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

本任务实现 `AgentScopeEngine`：把一轮的平台输入翻译成 AgentScope 调用，把 AgentScope 的事件流翻译成平台事件，并保证每一轮有且只有一个终态。同时实现 `FencedAgentStateStore`，让 AgentScope 在任何路径上的状态写入都受租约约束。

这些逻辑决定了"业务方看到的事件是否准确完整""崩溃后能否正确恢复"。它们全部在引擎模块内用 `ScriptedModel` 与内存端口验证，不依赖数据库；T05 起再接上真实的存储与调度。

## 必读

1. 架构设计「运行时架构」：「内核适配层」「身份映射」「引擎装配」「平台中间件」「租约与 fencing」「状态机」「事件映射」「消息投递」中的中断规则、「待处理动作」中审批结果与自定义工具结果的传递、「崩溃恢复」中的节点优雅下线。
2. 本任务之前已有的 `meridian-engine-spi`、`meridian-engine-agentscope` 代码与模块 `CLAUDE.md`。
3. 实现时查阅（AgentScope 2.0.4 源码，按需定位，不通读）：`ReActAgent` 的调用入口（含待确认调用存在时的分支）、`streamEvents`、`interrupt`、`saveAgentState`、`replacePermissionContext`、`clearStateCache`、`AgentEventType` 与各事件类、`GenerateReason`、`ConfirmResult`、`Msg.METADATA_CONFIRM_RESULTS`、`ToolRequestConfig`、`SchemaOnlyTool`、`ToolResultBlock`、`GracefulShutdownManager`、`GracefulShutdownConfig`、`AgentShuttingDownException`、`AgentStateStore` 及 `VersionedState`。

## 范围

### 要做

- `AgentScopeEngine`：`runTurn`、`interrupt`、`shutdown`。
- 输入翻译、事件映射与增量拼装、停止原因映射、终态保证。
- `FencedAgentStateStore`（基于 `AgentStateRepository` 端口）。
- 中间件：`PlatformContextMiddleware`、`LeaseGuardMiddleware`、`ToolSpanMiddleware`。
- `OtelTracingMiddleware` 的装配与 span 属性补充。

### 不做

- 数据库实现的端口（T05）、轮次 Worker 与恢复判定（T06）。
- 权限规则的合成逻辑（T08）。本任务只负责把 `TurnContext` 中已合成的 `PermissionPolicy` 翻译成 `PermissionContextState` 并安装。
- `SteerMiddleware`、`RecoveryNoteMiddleware`（T06、T08）、`CompactionGuardMiddleware`（T09a）、`PolicyMiddleware`（T10a）。

## 交付物

### 1. `runTurn` 的执行顺序

1. 由 `PlatformContext` 构造 `RuntimeContext`：`sessionId` 为线程 ID，`userId` 为 `{project_id}:{end_user_id}`（无终端用户时为 `{project_id}:_`），以类型键放入 `PlatformContext`。
2. 调用 `clearStateCache(userId, threadId)`；把 `PermissionPolicy` 翻译为 `PermissionContextState`，经 `getDelegate().replacePermissionContext(...)` 安装。安装前在 `LeaseRegistry` 条目上标记"权限安装写入"，安装后清除，供 `FencedAgentStateStore` 区分 `content_epoch`。
3. 按 `TurnInput` 的类型翻译输入：
   - `NewMessages`：平台消息转为 `Msg`。
   - `Confirmations`：在 Java 侧构造 `ConfirmResult` 实例，放入消息元数据 `Msg.METADATA_CONFIRM_RESULTS`。
   - `CustomToolResults`：转为 `ToolResultBlock`。
   - `RecoveryOnly`：不带入参。恢复说明由 T06 的中间件注入，本任务只保证这种输入能正常调用。
   - 自定义工具声明通过 `RuntimeContext` 的 `ToolRequestConfig` 注入 `SchemaOnlyTool`，合并模式 `MERGE_EXTERNAL_PRIORITY`。
4. 调用 `streamEvents`，在顶层 `contextWrite` 写入 `PlatformContext`。
5. 映射事件并输出；结束（含异常、取消）时在 `finally` 中再次 `clearStateCache`。

### 2. 事件映射

- 严格按架构设计「事件映射」表实现。结束类事件不带内容，按 `blockId` 与 `toolCallId` 拼装 `DELTA` 后输出完整内容。
- `TOOL_RESULT_END` 中 `permission` 的推导规则按表实现；超过 16 KB 的工具结果，经 `BlobStorePort`（架构设计「端口」表）写入大对象存储，事件中放引用；本任务用端口的内存实现，数据库与对象存储的实现由 T06 提供。
- 子线程事件不在本任务处理（`source` 为路径的事件只记日志，T13 处理）。
- 停止原因按「状态机」中的映射表实现。

### 3. 终态保证

- 每一轮输出的事件流以且仅以一个终态结束：`session.status_idle`（带 `stop_reason`），或 `session.status_requires_action`（带待处理动作描述），或 `session.status_rescheduled`。
- `Flux.error` 被捕获，先输出 `session.error`（带 `retry_status`），再输出终态。
- `AgentShuttingDownException` 映射为 `session.status_rescheduled`，不输出 `session.error`。
- 订阅被取消（失去租约）时不输出任何事件，也不触发任何写入。

### 4. 穷举映射测试

- 遍历 `AgentEventType.values()`（31 个）与 `GenerateReason.values()`，断言每个值都有明确的映射或明确的"忽略"决定。新增值会让测试失败。
- `TOOL_CALLS` 与未知值映射为 `error` 并记录告警日志。

### 5. `FencedAgentStateStore`

- 实现 `AgentStateStore` 的全部方法，支持版本号（`supportsVersioning` 为真，`saveIfVersion` 语义与 2.0.4 一致）。
- 每次写入（包括 `expectedVersion` 为无条件写）都委托 `AgentStateRepository` 端口在同一事务中校验租约：`LeaseRegistry` 中该线程的 epoch，以及节点身份。校验失败抛 `LeaseLostException`，什么都不写。
- 每次写入更新 `content_epoch` 为当前 epoch，权限安装写入除外（依据 `LeaseRegistry` 条目上的标记）。
- 子线程的写入使用父轮次的租约（`LeaseRegistry` 支持把子线程登记到父轮次的租约下）。

### 6. 中断与下线

- `interrupt(ThreadRef)`：调用 `HarnessAgent.interrupt(userId, threadId)`。是否命中正在执行的调用，返回布尔值。
- `shutdown(Duration)`：启动时设置 `GracefulShutdownConfig` 的超时为 45 秒；调用 `performGracefulShutdown()`，再 `awaitTermination`。与 AgentScope 自注册的 JVM 关闭钩子并存，二者都幂等。

### 7. 中间件

- `PlatformContextMiddleware`（`onAgent`）：校验 `PlatformContext` 存在，写入 MDC，开启轮次 span。
- `LeaseGuardMiddleware`（`onReasoning`、`onActing`）：每次推理与工具执行前向 `LeaseRegistry` 确认租约有效，失效则抛 `LeaseLostException`。
- `ToolSpanMiddleware`（`onActing`）：每个工具调用单独建 span。
- 记录各中间件的 `order()` 与实际执行顺序（P13 的一部分），写入模块 `CLAUDE.md`。

## 实现要点

- 写每一个映射分支前，先在 2.0.4 源码中确认事件的真实字段与触发时机，不要按事件名猜测。
- 增量拼装要处理交错：并行工具调用时，多个 `toolCallId` 的增量交错到达。
- 待确认调用存在时，AgentScope 会丢弃同批普通消息（ADR-0016）。用测试证明本任务的翻译不会产生这种组合。
- 场景测试全部使用 `ScriptedModel` 与内存端口，覆盖：纯文本、工具调用、并行工具、ask 后确认续跑、ask 后拒绝、自定义工具挂起与回传、首包前错误、首包后错误、中断、关机中断、失去租约。

## 验收标准

- [T04-A01] 身份映射符合架构设计：`sessionId` 为线程 ID，`userId` 格式正确，`PlatformContext` 可从 `RuntimeContext` 与 Reactor Context 两处取到。验证：单元测试。
- [T04-A02] 31 个 `AgentEventType` 与全部 `GenerateReason` 都有映射或明确忽略，穷举测试通过。验证：测试报告。
- [T04-A03] 文本与工具入参由增量正确拼装，并行工具调用交错到达时不串。验证：场景测试。
- [T04-A04] 每轮恰好一个终态：正常结束、错误、关机中断、待处理动作四种情况都满足。验证：场景测试。
- [T04-A05] 审批续跑：ask 后以 `Confirmations` 输入续跑，批准的调用执行、拒绝的调用写入拒绝结果。验证：场景测试。
- [T04-A06] 自定义工具：挂起后以 `CustomToolResults` 续跑，智能体收到结果并继续。验证：场景测试。
- [T04-A07] `FencedAgentStateStore`：租约有效时写入成功；租约失效时 `save`、`saveIfVersion`（含无条件写）、`delete` 都抛 `LeaseLostException` 且不写入；权限安装写入不更新 `content_epoch`，其他写入更新。验证：单元测试。
- [T04-A08] 订阅取消后不再输出事件、不再写状态。验证：场景测试（取消后检查内存端口无新写入）。
- [T04-A09] 中间件执行顺序已测出并记录，`LeaseGuardMiddleware` 在租约失效时阻止下一次推理与工具执行。验证：测试与模块 `CLAUDE.md`。
- [T04-A10] `scripts/dev/verify.sh` 通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 更新 `meridian-engine-agentscope` 的 `CLAUDE.md`：事件映射的入口类、终态保证的位置、中间件顺序、易错点。
- 在 `docs/poc/README.md` 中把 P13 标为"进行中"，写明本任务测得的中间件顺序；完整结论由 T09a 给出。
