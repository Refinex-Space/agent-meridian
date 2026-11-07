# T06 轮次 Worker、事件流水线与崩溃恢复

| 项 | 内容 |
|---|---|
| 阶段 | 1 M0 内核与运行时核心 |
| 依赖 | T05 |
| 推荐配置 | Opus 5.5，effort xhigh，新会话 |
| 规模 | 大 |
| 关联验证项 | P2（完整路径）、P3、P10 |

启动方式：在仓库根目录开新会话，输入 `/task T06`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；AgentScope 只以 2.0.4 为准（读法见 `docs/plan/references.md`）。
- 只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

本任务把调度与引擎接起来：`TurnWorker` 认领轮次、准备输入、调用 `AgentEngine`、把事件写入数据库并推送到 Redis、结束轮次并推进会话状态。然后实现崩溃恢复：节点被强杀、出错、被中断、优雅下线后，会话都能被其他节点正确续跑。

恢复是平台可靠性的核心承诺，也是最容易出错的地方：重复用户消息、丢失 steer、重复执行有副作用的工具，都是真实风险。验收用故障注入测试逐一证明。

## 必读

1. 架构设计「运行时架构」：「一轮执行的生命周期」「租约与 fencing」「状态机」「消息投递」「待处理动作」「崩溃恢复」（全节）。
2. 已有模块 `CLAUDE.md`：`meridian-runtime`、`meridian-engine-agentscope`。
3. `docs/poc/P02-租约与fencing.md`。
4. 实现时查阅（AgentScope 2.0.4 源码，按需定位，不通读）：状态保存的全部时机（正常结束、`saveStateAfterCallFailure`、`handleInterrupt`、`ShutdownStateSaver`）、`saveAgentState(RuntimeContext)`、`enablePendingToolRecovery` 的行为、停机中断标志对下一次调用入参的影响。

## 范围

### 要做

- `session` 上下文的 `TurnWorker` 与轮次结束逻辑；Redis Stream 推送（`stream` 上下文的写入侧）。
- 恢复判定、恢复说明生成、`RecoveryNoteMiddleware`。
- 优雅下线（`SmartLifecycle`）。
- 工具账本 `ToolLedgerPort` 的数据库实现（工具本身由 T10a 实现；本任务用测试工具驱动）。
- 收件箱：`inbox_messages` 仓储与 `InboxPort` 的数据库实现（queue 消息合并成下一轮、按 `consumed_epoch` 重新投递被失败执行消费的消息）。
- `BlobStorePort` 的实现（对象存储加引用），替换 T04 的内存实现。
- `recovery_confirmation` 待处理动作的创建（回应的处理在 T08）。
- M0 验证 P3、P10，补全 P2。
- 本任务涉及的指标：`meridian_turns_total`、`meridian_turn_duration_seconds`、`meridian_turn_queue_wait_seconds`、`meridian_dispatch_queue_depth`、`meridian_turn_recoveries_total`、`meridian_lease_lost_total`。

### 不做

- HTTP API 与 SSE（T07）、收件箱的 steer 与中断语义（T08）、待处理动作回应（T08）。
- 运行时的智能体配置来源：本任务用 `AgentConfigProvider` 端口（runtime 内部接口）的文件实现 `FileAgentConfigProvider`，从 `dev-config/agents/` 读取 YAML。它在 T15b 之后只保留给开发与测试使用。

## 交付物

### 1. `TurnWorker`

按架构设计生命周期时序图实现，顺序如下：

1. 认领（T05）。
2. 读取 `turn_inputs`；如果是恢复认领，先判定恢复方式（见下文），再继续。
3. 构造 `TurnContext`（含 `agent_version_id`、`environment_id`、`environment_revision`）。引擎实例的获取与引用计数在 `AgentScopeEngine.runTurn` 内部完成，runtime 只依赖 `AgentEngine` 接口（ArchUnit 规则 2）。
4. 调用 `runTurn`。每个事件经 epoch 校验写入 `session_events`，成功后 XADD 到 `ev:{session_id}`。
5. 终态事件到达后，在一个事务中：结束轮次、租户并发计数减一、更新线程与会话状态、写 outbox；存在待投递的 queue 消息时插入下一轮。
6. `finally` 中从 `LeaseRegistry` 移除条目。

失去租约时只取消订阅，**禁止**调用 `interrupt`。

### 2. 恢复

严格按架构设计「崩溃恢复」实现：

- 认领恢复轮次时，**先**读取线程状态的 `content_epoch` 与失败那次执行的 `lease_epoch` 比较，**再**清理缓存、安装权限。
- 不相等：重放 `turn_inputs` 的完整输入，重新投递被失败执行消费的 steer 消息（`consumed_epoch` 等于失败的 epoch）。
- 相等：输入类型为 `RecoveryOnly`。
- 两种情况都附恢复说明。恢复说明由 `tool_invocations` 生成：已成功的调用（附结果摘要）与状态未知的调用。`RecoveryNoteMiddleware` 在恢复轮第一次推理前注入，不走调用入参。
- 状态未知的 `non_idempotent` 调用，创建 `recovery_confirmation` 待处理动作，会话转为 `requires_action`。
- `recovery_attempt` 达到 2 次，轮次以 `stop_reason=error` 结束。

### 3. 批次级 checkpoint（P3）

验证在每批工具执行完成后调用 `saveAgentState(ctx)` 能否得到一致的 checkpoint：强杀后从该 checkpoint 续跑，tool_use 与 tool_result 配对、不重复执行已完成的工具。

- 验证通过：实现为默认开启的行为，配置项可关闭，写 ADR（补充型）。
- 验证不通过：保持轮次级恢复，在报告中写明原因。

### 4. 优雅下线

一个 `SmartLifecycle` 组件，phase 排在数据源关闭之前：停止认领 → `AgentEngine.shutdown(60s)` → 把本节点所有轮次置回 `queued`（`recovery_attempt` 不变）并释放租约。

### 5. 故障注入测试（P10、P2 完整路径）

在测试中模拟四种终止，每种都验证续跑后的结果：

| 终止方式 | 模拟方法 | 期望 |
|---|---|---|
| 进程被强杀 | 取消执行、不运行任何收尾，让租约过期，回收器置回 | 重放完整输入；不重复用户消息；不丢 steer |
| 出错 | `ScriptedModel` 在工具执行后注入错误 | `RecoveryOnly`；不重复已完成的工具 |
| 用户中断 | 执行中调用中断 | 本轮以 `interrupted` 结束；后续消息成为新一轮 |
| 优雅下线 | 调用 `shutdown` | 轮次置回队列，`recovery_attempt` 不变；另一个 Worker 续跑成功 |

另外验证：旧 Worker 在失去租约后的残余写入（状态、事件、账本）全部被拒绝。

## 实现要点

- 恢复判定是纯逻辑，单独抽成可单测的类，输入为状态与账本快照，输出为恢复方式与说明。
- Redis XADD 失败不回滚数据库事件（数据库是事实来源）；记录指标，由 SSE 的对账机制兜底（T07）。
- 测试用多个 Worker 实例在同一 JVM 中模拟多节点，节点 ID 不同。

## 验收标准

- [T06-A01] 端到端：插入一轮输入后，Worker 认领并用 `ScriptedModel` 执行完成，事件按序写入数据库并出现在 Redis Stream 中，会话回到 `idle`，`stop_reason` 正确。验证：集成测试。
- [T06-A02] 本轮执行中到达的 queue 消息，在本轮结束时合并成下一轮，并被自动执行。验证：集成测试。
- [T06-A03] P10 四种终止方式全部按上表恢复，且不重复用户消息、不重复执行 `non_idempotent` 工具；steer 的重新投递，通过直接写入 `consumed_epoch` 等于失败 epoch 的收件箱记录来验证（`SteerMiddleware` 在 T08 实现，届时重跑）。验证：故障注入测试；报告 `docs/poc/P10-恢复语义.md`。
- [T06-A04] P2 完整路径：失去租约的旧 Worker 在中断、出错、停机路径上都写不进状态、事件、账本。验证：测试；补全 `docs/poc/P02-租约与fencing.md` 并定稿。
- [T06-A05] P3：批次级 checkpoint 的验证结论与数据已写入 `docs/poc/P03-批次checkpoint.md`，并按结论实现或保持轮次级恢复。验证：报告与测试。
- [T06-A06] 状态未知的 `non_idempotent` 调用产生 `recovery_confirmation` 待处理动作，会话进入 `requires_action`。验证：集成测试。
- [T06-A07] `recovery_attempt` 达到 2 次后以 `error` 结束。验证：测试。
- [T06-A08] 优雅下线：本节点轮次置回队列且 `recovery_attempt` 不变，另一节点续跑成功。验证：集成测试。
- [T06-A09] 本任务列出的指标已注册并在测试中有值。验证：测试断言指标。
- [T06-A10] `scripts/dev/verify.sh` 通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 更新 `meridian-runtime` 的 `CLAUDE.md`：Worker 的入口、恢复判定的位置、故障注入测试的写法。
- 更新 `docs/poc/README.md` 中 P2、P3、P10 的状态。
