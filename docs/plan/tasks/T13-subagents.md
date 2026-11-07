# T13 子智能体

| 项 | 内容 |
|---|---|
| 阶段 | 1 M0 内核与运行时核心 |
| 依赖 | T12b |
| 推荐配置 | Opus 5.5，effort xhigh，新会话 |
| 规模 | 中 |
| 关联验证项 | P12 |

启动方式：在仓库根目录开新会话，输入 `/task T13`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；AgentScope 只以 2.0.4 为准（读法见 `docs/plan/references.md`）。
- 只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

主智能体可以按需拉起子智能体分担工作，子智能体有独立的上下文、更窄的工具集，结果以工具结果的形式回到主智能体。架构设计规定由平台工具 `spawn_subagent` 实现（ADR-0012），不用 harness 的 `agent_spawn`，原因是后者在身份、共享状态、中间件继承、审批上不满足多租户要求。

本任务实现 `spawn_subagent`，以及子线程在事件、权限、中断、计量、沙箱上的全部行为。

## 必读

1. 架构设计「运行时架构」：「子智能体」（全节）、「身份映射」、「权限判定」中的改写、「消息投递」中中断传递到子线程的规则；「API 设计」中线程相关的端点与事件。
2. 已有模块 `CLAUDE.md`。
3. 实现时查阅（AgentScope 2.0.4 源码，按需定位，不通读）：`AgentSpawnTool`（理解为什么不用它）、`RuntimeContext.builder()` 与 `builder(parent)` 的区别（后者复制的内容）、`HarnessAgent.interrupt`。

## 范围

### 要做

- `SpawnSubagentTool`（继承 `PlatformTool`）与子智能体的装配、缓存。
- 子线程的创建、事件写入、状态事件、计量归属、沙箱共享、权限改写、中断传递、超时、拉起数量上限。
- 线程相关 API 的数据（`GET /v1/sessions/{id}/threads`、线程流）。
- M0 验证 P12。

### 不做

- 后台子智能体与团队（M2，T32）。
- 嵌套子智能体（深度固定为 1）。

## 交付物

### 1. `spawn_subagent`

严格按架构设计「子智能体」的六步实现：

1. 参数 `subagent` 与 `task`；可用子智能体来自 `ResolvedAgentSpec`，工具集必须是父智能体的子集（装配时校验，不满足则装配失败）。
2. 经 `ThreadRegistryPort`（架构设计「端口」表）创建子线程（记录 `parent_tool_use_id`），发出 `session.thread_created`；用 `RuntimeContext.builder()` **从空白构建**子上下文，只放入派生的 `PlatformContext` 与会话沙箱的 `SandboxContext`；**禁止** `builder(parent)`。
3. 子智能体的 `HarnessAgent` 由 `AgentAssembler` 按子智能体配置装配与缓存；挂载子智能体专用的中间件集合（不含 `SteerMiddleware`、`KnowledgePrefetchMiddleware`、`spawn_subagent`）。
4. 子线程权限取 `TurnContext.subagentPolicies` 中由 runtime 预先合成的策略（T08）；子智能体以 `PERMISSION_ASKING` 或 `TOOL_SUSPENDED` 结束时，转为一条拒绝结果返回父智能体。
5. 调用子智能体的 `streamEvents`，事件经 `EventSinkPort` 写为子线程事件（带子线程 `thread_id`），并发出 `session.thread_status_running` 与 `session.thread_status_idle`；最终消息作为工具结果返回。
6. 同步执行，超时取子智能体配置（上限 600 秒），超时后取消子智能体的订阅并返回超时结果。

### 2. 配套行为

- 子线程状态写入使用父轮次的租约（`LeaseRegistry` 登记）。
- 中断：`AgentEngine.interrupt` 先中断本轮登记的全部子线程，再中断父线程。
- 拉起数量：`PolicyMiddleware` 限制每轮最多 8 次（可配置）。
- 计量：子智能体的模型用量计入父会话，账本中带子线程 ID。
- 沙箱：子线程使用同一个会话沙箱（T12a 已支持）。
- 删除会话时清理子线程的 `agent_states`。

## 实现要点

- 并行拉起：Toolkit 默认并行执行工具，两个同名子智能体同时运行时，各自的线程 ID 不同，事件不会混淆。用测试证明。
- 子智能体的事件量可能很大：会话流包含全部线程的事件；业务方可以用 `?threads=main` 只订阅主线程与线程状态事件（T07 已实现）。
- 子智能体的模型可以与父智能体不同，用两个不同的 `ScriptedModel` 验证模型选择正确。

## 验收标准

- [T13-A01] 主智能体调用 `spawn_subagent` 后，子线程被创建、执行、返回结果，父智能体基于结果继续。验证：场景测试。
- [T13-A02] 子上下文不包含父线程的 `AgentState`、`ToolRequestConfig`（自定义工具）与文件系统对象。验证：测试断言。
- [T13-A03] P12：同时拉起两个同名子智能体，两条子线程的事件互不混淆；中断父线程时两条子线程都停止。验证：测试；报告 `docs/poc/P12-子智能体.md`。
- [T13-A04] 子智能体遇到需要审批的工具时被拒绝（存在显式 ask 规则时同样如此），父智能体收到拒绝结果。验证：场景测试。
- [T13-A05] 超时后子智能体被取消，父智能体收到超时结果。验证：场景测试。
- [T13-A06] 每轮超过拉起上限时，多余的调用被拒绝。验证：场景测试。
- [T13-A07] 子智能体的用量计入父会话，账本带子线程 ID。验证：集成测试。
- [T13-A08] `GET /v1/sessions/{id}/threads` 与线程流返回正确的数据；`?threads=main` 的会话流只含主线程事件与线程状态事件。验证：集成测试。
- [T13-A09] `scripts/dev/verify.sh` 通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 更新 `meridian-engine-agentscope` 与 `meridian-runtime` 的 `CLAUDE.md`。
- 更新 `docs/poc/README.md` 中 P12 的状态。
