# T08 消息投递、待处理动作与权限合成

| 项 | 内容 |
|---|---|
| 阶段 | 1 M0 内核与运行时核心 |
| 依赖 | T07 |
| 推荐配置 | Opus 5.5，effort xhigh，新会话 |
| 规模 | 大 |
| 关联验证项 | P6（审批流程部分）、P15 |

启动方式：在仓库根目录开新会话，输入 `/task T08`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；AgentScope 只以 2.0.4 为准（读法见 `docs/plan/references.md`）。
- 只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

本任务实现"人和智能体如何在执行过程中互动"：运行中发来的消息怎么处理（queue、steer、interrupt），智能体需要人回应时怎么暂停和继续（审批、计划退出、自定义工具、恢复确认），以及每一轮的权限规则如何从五个层级合成。

这三块决定了平台的安全边界和交互体验。权限合成错误会让智能体越权或被不必要地卡住；投递语义错误会丢消息或产生混乱的轮次。

## 必读

1. 架构设计「运行时架构」：「消息投递」「待处理动作」「权限判定」「子智能体」（第 4 条，子线程的权限改写）、「平台工具」中的「保留工具名」。
2. 已有模块 `CLAUDE.md`：`meridian-runtime`、`meridian-engine-agentscope`。
3. 实现时查阅（AgentScope 2.0.4 源码，按需定位，不通读）：`PermissionEngine` 的判定流程（显式 ask 规则与 `DONT_ASK` 的关系、安全类的工具自检 ask）、`PermissionRule`、`PermissionContextState`、`ToolBase.matchRule`、`ConfirmResult`、`PlanExitTool.checkPermissions`、`interrupt` 的检查点。

## 范围

### 要做

- `delivery` 上下文：收件箱的三种投递方式在三种线程状态下的全部行为（架构设计中的表）；`SteerMiddleware`；中断的数据库标记、节点频道通知、引擎调用。
- `approval` 上下文：四种待处理动作的创建、回应、超时、恢复轮生成规则。
- 权限合成：五层规则合并、模式、`unattended` 与子线程的改写、`plan_enter` 禁止规则、"记住此决定"。
- 自定义工具：声明校验（含保留名）、`ToolRequestConfig` 注入、挂起与回传。
- `GET /v1/sessions/{id}/pending-actions` 的实现。
- 审计事件：每次权限判定结果、每次待处理动作的回应，经 outbox 写入 `audit` 主题。

### 不做

- 平台工具本身（T10a、T10b）与 bash（T12a）。本任务用一个实现了 `matchRule` 的测试工具验证规则匹配。
- 项目级"记住此决定"的持久化目标是 control-plane（T15b）。本任务通过 `PolicyStorePort`（runtime 内部端口）写入，提供一个 `rt` 表实现作为开发实现。
- 子智能体（T13）。本任务只实现子线程的权限改写函数，并用单元测试验证。

## 交付物

### 1. 投递

- 架构设计「消息投递」表中 queue、steer、interrupt 与删除会话在空闲、运行中、等待回应三种状态下的每个格子都有实现与测试。
- `SteerMiddleware`（`onReasoning`）：读取本线程待投递的 steer 消息，追加到本次推理输入与 `AgentState` 上下文，记录 `consumed_turn_id` 与 `consumed_epoch`，产生 `session.message_consumed` 事件。本轮在注入前结束时，未消费的 steer 降级为 queue。
- 中断：写 `turns.interrupt_requested_at`；向 `node:{lease_owner}` 频道发布；持租约节点收到后调用 `AgentEngine.interrupt`。Pub/Sub 丢失时由续租检查兜底（最多 10 秒）。`queued` 状态的轮次被中断时直接置为 `cancelled`。

### 2. 待处理动作

- 四种类型按架构设计「待处理动作」表：触发、回应事件、超时处理（默认 30 分钟，可按智能体配置）。
- 超时调度：多节点下只处理一次（`SKIP LOCKED`）；扫描仓储继承 `SystemRepository`，放在 `infra.system`。
- 恢复轮生成：一轮的全部动作都有回应后才生成；同批既有待确认调用又有自定义工具时，先 `confirmations` 轮，再 `custom_tool_results` 轮；`recovery_confirmation` 生成 `RecoveryOnly` 轮，`retry` 由平台在 Java 侧用原幂等键重新执行该工具调用，把结果写入恢复说明。
- `awaiting_action` 期间收到 `user.interrupt`：对所有待处理动作生成拒绝结果，运行一个恢复轮后停止。
- 审批结果传递：构造 `ConfirmResult` 实例（T04 的翻译层已支持）。

### 3. 权限合成

- `PermissionSynthesizer`（纯逻辑，可单测）：输入为平台安全规则、租户策略、项目策略、智能体版本策略、会话规则、会话 `interaction`、线程是否为子线程；输出为平台的 `PermissionPolicy`。
- 平台安全规则：写受保护路径（`AGENTS.md`、`skills/`、`subagents/`、`tools.json`、`MEMORY.md`）、读凭据类文件，最高优先级 deny。
- 模式：取智能体配置，会话只能收紧。
- 改写：`unattended` 会话与子线程使用 `DONT_ASK`，并把全部 ask 规则改写为 deny；`unattended` 会话额外 deny `plan_enter`。
- 兜底：`unattended` 会话仍以 `PERMISSION_ASKING` 结束时（例如安全类工具自检 ask），不产生待处理动作，自动生成拒绝结果并运行恢复轮。
- "记住此决定"：`remember: session` 写入会话级规则，`remember: project` 经 `PolicyStorePort` 写入项目级规则；之后每轮合成时生效。
- 子智能体的权限：轮次开始时，为本智能体配置的每个子智能体预先合成权限（子线程改写规则），放进 `TurnContext.subagentPolicies`，供 T13 的 `spawn_subagent` 使用。

### 4. 自定义工具

- 名称规则 `^[a-z][a-z0-9_]{0,63}$`，不能在保留工具名内。保留名单以 `AgentAssembler` 装配后从 Toolkit 读出的名称为准，加上 `mcp__` 前缀。
- 每轮通过 `ToolRequestConfig` 注入 `SchemaOnlyTool`；调用后本轮以 `TOOL_SUSPENDED` 结束，产生 `agent.custom_tool_use` 与 `custom_tool_result` 待处理动作。

## 实现要点

- 对照 2.0.4 源码确认：显式 ask 规则在 `DONT_ASK` 下仍返回 ask（这是改写的原因）；`ConfirmResult.rules` 不会被持久化（这是"记住此决定"由平台保存的原因）。确认后写进模块 `CLAUDE.md` 的易错点。
- 投递语义的测试按状态表逐格编写，测试名中写明"状态 × 投递方式"，便于对照。
- 权限合成的测试覆盖五层优先级冲突、模式收紧、两种改写、兜底自动拒绝。

## 验收标准

- [T08-A01] 投递表的每个格子都有通过的测试。验证：测试名与表格的对照清单。
- [T08-A02] steer 在当前轮下一次推理前被注入并记录 `consumed_epoch`；注入前本轮结束时降级为 queue。验证：集成测试。
- [T08-A03] 中断在 Pub/Sub 正常时于一个检查点内生效；Pub/Sub 丢失时于 10 秒内生效；`queued` 轮次被中断后为 `cancelled`。验证：集成测试。
- [T08-A04] 审批：ask 产生 `tool_confirmation` 待处理动作；批准后工具执行，拒绝后智能体收到拒绝结果并继续；超时视为拒绝。验证：集成测试（测试工具实现 `matchRule`）。
- [T08-A05] 同批既有待确认调用又有自定义工具时，两类恢复轮按先确认、后工具结果的顺序生成，没有消息被丢弃。验证：集成测试。
- [T08-A06] P15：存在显式 ask 规则时，`unattended` 会话与子线程都不会停在等待审批；`unattended` 会话无法进入计划模式。验证：测试；报告 `docs/poc/P15-权限改写.md`。
- [T08-A07] "记住此决定"的会话级与项目级规则在后续轮次中生效。验证：集成测试。
- [T08-A08] 自定义工具：非法名称与保留名称在创建会话时被拒绝；合法工具挂起、回传、续跑成功。验证：集成测试。
- [T08-A09] 权限判定与待处理动作回应产生审计 outbox 记录。验证：集成测试。
- [T08-A10] 用 `SteerMiddleware` 重跑 P10 的强杀场景：被失败执行消费的 steer 被重新投递，不丢失；补全 `docs/poc/P10-恢复语义.md`。验证：故障注入测试。
- [T08-A11] `scripts/dev/verify.sh` 通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 更新 `meridian-runtime` 与 `meridian-engine-agentscope` 的 `CLAUDE.md`。
- 在 `docs/poc/README.md` 中更新 P6（审批流程部分已验证，文件工具规则在 T10a，bash 规则在 T12a）与 P15。
