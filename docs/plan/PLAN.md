# AgentMeridian 交付计划

这份文件是项目交付的总控台：怎么用任务提示词推进项目、每个任务的状态、当前进行中任务的检查点、每次会话的记录。每个任务开工时都先读它，收尾时都更新它。

## 怎么使用

### 开工前（只做一次）

规划文件提交之后才能开始 T01：执行协议的开工检查要求工作区干净。由你确认后执行：

```bash
git add CLAUDE.md docs .claude/commands .claude/rules .claude/settings.json scripts
git commit -m "docs: 建立交付计划、执行协议与工程规范"
```

`.claude/settings.local.json` 是本机配置（`JAVA_HOME`、参考源码目录），已被 Git 忽略，不提交。

### 每个任务的步骤

1. **准备环境**：任务需要 kind 集群或大量容器时（T11、T12a、T12b、T17a、T28、T29），先关闭不用的应用，确认 Docker Desktop 的可用内存。T28 的三节点集群与完整部署需要把 Docker 内存调到 12 GB 以上。
2. **开新会话**：在仓库根目录启动 Claude Code，或在已有会话中输入 `/clear`。一个任务一个会话，不要在同一个会话里连续做两个任务。
3. **设置模型与 effort**：模型用 Opus 5.5；effort 按看板中的推荐值，用 `/effort high` 或 `/effort xhigh` 设置。
4. **启动任务**：输入 `/task T05`（把编号换成要做的任务），或者把 `docs/plan/tasks/` 下对应文件的全部内容粘贴进去。两种方式等价。
5. **可选：设置完成条件**：长任务可以再输入一条 `/goal`，例如 `/goal docs/plan/acceptance.json 中 T05 的验收项全部为 passing 或 blocked，且 PLAN.md 中 T05 的状态为已完成`。Claude Code 会在每轮结束时检查它，没达成就继续。
6. **看报告**：任务结束时智能体给出固定格式的报告。重点看"验收结果"中的失败与阻塞项，以及"遗留与风险"中需要你决定的事。
7. **推送**：智能体只提交不推送。确认无误后由你执行 `git push`。

### 中断了怎么办

会话中断、电脑休眠、你手动停止都没关系。开新会话，再输入一次 `/task T05`。执行协议会识别出任务处于"进行中"，从下面的"当前任务检查点"继续，不会从头再做。

### 需要你做决定的时候

智能体遇到与架构设计冲突的情况，会写一份状态为"提议"的 ADR 并停下受影响的部分。你读完 ADR 后，在新会话中告诉 Claude"采纳 ADR-00xx"或"否决 ADR-00xx，理由是……"，它会更新 ADR 状态、同步架构设计，然后你再继续该任务。

### 执行顺序

- 按看板从上到下执行。评审任务（R1 至 R6）是阶段门，不能跳过：它们的结论决定能否进入下一阶段。
- 唯一可以并行的是**控制台设计轨道**（T20a、T20b、T20c）：T07 完成后，在独立的 worktree 中与后端任务并行，做法见下文「控制台设计轨道」。主工作区同一时间只执行一个任务。
- 几个任务会在中途停下等你确认，这是计划的一部分：T20a（视觉方向）、T31、T32、T34、T35、T37（设计增补），以及任何写了"提议"状态 ADR 的任务。
- M2、M3 的任务之间依赖较少，可以按业务优先级调整顺序，但都必须在 R4 之后。

## 阶段

| 阶段 | 内容 | 任务 | 阶段门 | 状态 |
|---|---|---|---|---|
| 0 工程基座 | 脚手架、质量门禁、通用基础库、本地基础设施 | T01–T02 | — | 已完成 |
| 1 M0 内核与运行时核心 | 引擎契约与适配、调度与租约、恢复、会话 API 与 SSE、投递与权限、模型层与脚本化模型服务、工具、沙箱与出网、子智能体；M0 验证 P1–P15 | T03–T13 | R1 | 进行中 |
| 2 M1 控制面与治理 | 租户与 API Key、OIDC 与服务 JWT、凭据与模型目录、智能体与配置分发、计量预算审计、运行时补全 | T14a–T17b | — | 未开始 |
| 3 M1 知识库与后端收口 | 写入、检索与接入、控制台所需的后端接口 | T18–T19b | R2 | 未开始 |
| 4 M1 控制台 | 设计方向与组件、原型（并行轨道）、工程基座、五组页面 | T20a–T25b | R3 | 未开始 |
| 5 M1 交付 | SDK 与接入文档、可观测性、部署、端到端验收 | T26–T29 | R4 | 未开始 |
| 6 M2 扩展能力 | AgentScope 2.1 评估、长期记忆、后台子智能体与团队、分叉与回退、智能问数、Web 终端、A2A 与 AG-UI、安全加固 | T30–T37 | R5 | 未开始 |
| 7 M3 生态能力 | JEV、外部 Agent Worker、浏览器沙箱、IM 渠道与定时任务、轨迹评估 | T38–T42 | R6 | 未开始 |

## 任务看板

状态取值：未开始、进行中、部分完成（有 `blocked` 验收项）、已完成、阻塞（等待用户决定）。"完成提交"填任务最后一个提交的短哈希。

| 编号 | 任务 | 阶段 | 依赖 | effort | 规模 | 验证项 | 状态 | 完成提交 |
|---|---|---|---|---|---|---|---|---|
| T01 | [工程脚手架与质量门禁](tasks/T01-scaffold-and-quality-gates.md) | 0 | 无 | high | 中 | P1（依赖解析部分） | 已完成 | b29c1af |
| T02 | [通用基础库、持久化与本地基础设施](tasks/T02-common-persistence-infra.md) | 0 | T01 | high | 大 | 无 | 已完成 | 4812ea7 |
| T03 | [内核契约、ScriptedModel 与 HarnessAgent 装配](tasks/T03-engine-contracts-and-assembly.md) | 1 | T02 | xhigh | 大 | P1 | 已完成 | 6038ef8 |
| T04 | [单轮执行适配与事件映射](tasks/T04-engine-turn-execution.md) | 1 | T03 | xhigh | 大 | P13（部分） | 已完成 | 0943c89 |
| T05 | [运行时数据层与调度租约](tasks/T05-runtime-data-and-dispatch.md) | 1 | T04 | xhigh | 大 | P2 | 已完成 | c1fd533 |
| T06 | [轮次 Worker、事件流水线与崩溃恢复](tasks/T06-turn-worker-and-recovery.md) | 1 | T05 | xhigh | 大 | P2（完整路径）、P3、P10 | 已完成 | c6ca61a |
| T07 | [会话 API 与 SSE](tasks/T07-session-api-and-sse.md) | 1 | T06 | high | 大 | 无 | 已完成 | 708f7ca |
| T08 | [消息投递、待处理动作与权限合成](tasks/T08-delivery-actions-permissions.md) | 1 | T07 | xhigh | 大 | P6（审批流程部分）、P15 | 进行中 | — |
| T09a | [模型层：RoutedModel、限流、预算、计量与压缩守护](tasks/T09a-routed-model-budget-compaction.md) | 1 | T08 | xhigh | 大 | P13 | 未开始 | — |
| T09b | [模型直通接口、Embedding 与 rerank、脚本化模型服务](tasks/T09b-model-endpoints-and-scripted-server.md) | 1 | T09a | high | 中 | 无 | 未开始 | — |
| T10a | [平台工具基类、文件工具、工作区存储与技能](tasks/T10a-tools-files-workspace-skills.md) | 1 | T09b | high | 大 | P4（`none` 档）、P6（文件工具规则） | 未开始 | — |
| T10b | [Web 工具、知识检索工具、MCP 客户端与提示词分层](tasks/T10b-web-knowledge-mcp-prompts.md) | 1 | T10a | high | 大 | P8（预验证） | 未开始 | — |
| T11 | [沙箱运行时镜像、集群与驱动](tasks/T11-sandbox-runtime-and-driver.md) | 1 | T10b | high | 大 | P5 | 未开始 | — |
| T12a | [会话沙箱生命周期、bash 与产物](tasks/T12a-session-sandbox-and-bash.md) | 1 | T11 | xhigh | 大 | P4（`sandbox` 档）、P6（bash 规则）、P9 | 未开始 | — |
| T12b | [沙箱出网控制：Envoy、ext_authz 与 NetworkPolicy](tasks/T12b-sandbox-egress-control.md) | 1 | T12a | xhigh | 中 | P14 | 未开始 | — |
| T13 | [子智能体](tasks/T13-subagents.md) | 1 | T12b | xhigh | 中 | P12 | 未开始 | — |
| R1 | [M0 评审与收口](tasks/R1-m0-review.md) | 1 | T13 | high | 中 | P7、P11，以及 P1 至 P15 的汇总 | 未开始 | — |
| T14a | [control-plane 基础：租户、成员、API Key 与限流](tasks/T14a-control-plane-tenancy-apikeys.md) | 2 | R1（结论为进入 M1） | high | 大 | 无 | 未开始 | — |
| T14b | [身份：OIDC、Keycloak 与服务 JWT](tasks/T14b-oidc-and-service-jwt.md) | 2 | T14a | high | 中 | 无 | 未开始 | — |
| T15a | [凭据库、供应商、模型目录与价格表](tasks/T15a-secrets-providers-models.md) | 2 | T14b | high | 大 | 无 | 未开始 | — |
| T15b | [智能体版本、环境、技能、MCP 与配置分发](tasks/T15b-agents-environments-config.md) | 2 | T15a | high | 大 | 无 | 未开始 | — |
| T16 | [计量、预算与审计](tasks/T16-metering-budget-audit.md) | 2 | T15b | high | 中 | 无 | 未开始 | — |
| T17a | [运行时补全：Webhook、会话删除、事件归档与保留](tasks/T17a-webhooks-deletion-retention.md) | 2 | T16 | high | 中 | 无 | 未开始 | — |
| T17b | [运行时补全：文件、工作区、用量事件与 Responses](tasks/T17b-files-workspace-responses.md) | 2 | T17a | high | 中 | 无 | 未开始 | — |
| T18 | [知识库写入](tasks/T18-knowledge-ingestion.md) | 3 | T17b | high | 大 | 无 | 未开始 | — |
| T19 | [知识库检索与接入](tasks/T19-knowledge-retrieval.md) | 3 | T18 | high | 中 | P8（定稿） | 未开始 | — |
| T19b | [控制台所需的后端接口](tasks/T19b-console-backend-apis.md) | 3 | T19 | high | 大 | 无 | 未开始 | — |
| R2 | [M1 后端评审](tasks/R2-backend-review.md) | 3 | T19b | high | 中 | 无 | 未开始 | — |
| T20a | [控制台设计方向：工程、令牌、图标与时间轴原型](tasks/T20a-console-direction-and-timeline.md) | 4 | T07（在独立 worktree 中与后端任务并行） | xhigh | 大 | 无 | 见控制台设计轨道 | — |
| T20b | [控制台基元与领域组件](tasks/T20b-console-components.md) | 4 | T20a（视觉方向已确认） | high | 大 | 无 | 见控制台设计轨道 | — |
| T20c | [控制台原型与设计评审](tasks/T20c-console-prototypes-and-review.md) | 4 | T20b | xhigh | 中 | 无 | 见控制台设计轨道 | — |
| T21 | [控制台工程基座](tasks/T21-console-foundation.md) | 4 | T20c（`console/T20` 已合并回 `main`）、R2 | high | 大 | 无 | 未开始 | — |
| T22 | [控制台：智能体与环境](tasks/T22-console-agents-environments.md) | 4 | T21 | high | 大 | 无 | 未开始 | — |
| T23 | [控制台：能力、模型与凭据](tasks/T23-console-capabilities-models-secrets.md) | 4 | T22 | high | 中 | 无 | 未开始 | — |
| T24 | [控制台：会话中心与 Playground](tasks/T24-console-sessions-playground.md) | 4 | T23 | xhigh | 大 | 无 | 未开始 | — |
| T25a | [控制台：知识库、待处理与概览](tasks/T25a-console-knowledge-pending-overview.md) | 4 | T24 | high | 大 | 无 | 未开始 | — |
| T25b | [控制台：用量与预算、设置](tasks/T25b-console-usage-settings.md) | 4 | T25a | high | 大 | 无 | 未开始 | — |
| R3 | [控制台体验评审](tasks/R3-console-ux-review.md) | 4 | T25b | high | 中 | 无 | 未开始 | — |
| T26 | [Java SDK 与 API 接入文档](tasks/T26-sdk-and-api-docs.md) | 5 | R2 | high | 中 | 无 | 未开始 | — |
| T27 | [可观测性](tasks/T27-observability.md) | 5 | R2 | high | 中 | 无 | 未开始 | — |
| T28 | [部署与发布](tasks/T28-deployment-and-release.md) | 5 | T27、R3 | high | 大 | 无 | 未开始 | — |
| T29 | [M1 端到端验收](tasks/T29-m1-acceptance.md) | 5 | T26、T28 | high | 大 | 无 | 未开始 | — |
| R4 | [M1 最终评审](tasks/R4-m1-final-review.md) | 5 | T29 | high | 中 | 无 | 未开始 | — |
| T30 | [AgentScope 2.1 升级评估](tasks/T30-agentscope-2.1-evaluation.md) | 6 | R4；AgentScope 2.1 已发布正式版（不是 BETA） | xhigh | 中 | 无 | 未开始 | — |
| T31 | [长期记忆](tasks/T31-long-term-memory.md) | 6 | R4 | xhigh | 大 | 无 | 未开始 | — |
| T32 | [后台子智能体与团队](tasks/T32-background-subagents-teams.md) | 6 | R4 | xhigh | 大 | 无 | 未开始 | — |
| T33 | [会话分叉与回退](tasks/T33-session-fork-rewind.md) | 6 | R4 | high | 中 | 无 | 未开始 | — |
| T34 | [智能问数](tasks/T34-text-to-sql.md) | 6 | R4 | xhigh | 大 | 无 | 未开始 | — |
| T35 | [Web 终端](tasks/T35-web-terminal.md) | 6 | R4 | high | 中 | 无 | 未开始 | — |
| T36 | [A2A 与 AG-UI](tasks/T36-a2a-agui.md) | 6 | R4 | high | 中 | 无 | 未开始 | — |
| T37 | [安全加固：行级安全、MCP OAuth 与出网凭据注入](tasks/T37-security-hardening.md) | 6 | R4 | xhigh | 大 | 无 | 未开始 | — |
| R5 | [M2 验收评审](tasks/R5-m2-review.md) | 6 | T30 至 T37（已完成或有明确结论） | high | 中 | 无 | 未开始 | — |
| T38 | [JEV 判断层](tasks/T38-jev-judgment-layer.md) | 7 | R5 | high | 中 | 无 | 未开始 | — |
| T39 | [外部 Agent Worker](tasks/T39-external-agent-workers.md) | 7 | R5 | xhigh | 大 | 无 | 未开始 | — |
| T40 | [浏览器沙箱](tasks/T40-browser-sandbox.md) | 7 | R5 | high | 中 | 无 | 未开始 | — |
| T41 | [IM 渠道与定时任务](tasks/T41-im-channels-scheduling.md) | 7 | R5 | high | 大 | 无 | 未开始 | — |
| T42 | [轨迹评估](tasks/T42-trajectory-evaluation.md) | 7 | R5 | high | 中 | 无 | 未开始 | — |
| R6 | [M3 验收评审](tasks/R6-m3-review.md) | 7 | T38 至 T42（已完成或有明确结论） | high | 中 | 无 | 未开始 | — |

## 架构覆盖

架构设计的每个章节都有任务负责落地。修改计划时，先检查这张表，确保没有章节失去负责的任务。

| 架构设计章节 | 落地任务 |
|---|---|
| 技术基线、代码仓库与模块、防漂移机制 | T01、T02，各任务持续维护 |
| 领域模型、ID 规则 | T02、T05、T14a、T15b |
| 数据架构 | T02、T05、T14a、T15a、T15b、T16、T17a |
| 内核适配层、身份映射、引擎装配 | T03、T04 |
| 平台中间件 | T04、T06、T08、T09a、T10a、T19 |
| 平台工具 | T10a、T10b、T12a、T13、T19、T34 |
| 一轮执行的生命周期、租约与 fencing | T05、T06 |
| 状态机、事件映射 | T04、T06 |
| 消息投递、待处理动作、权限判定 | T08 |
| 子智能体 | T13、T32 |
| 崩溃恢复 | T06 |
| 上下文管理、模型调用 | T09a、T09b、T10b |
| 工作区与沙箱 | T10a、T11、T12a、T12b |
| 知识库架构 | T18、T19 |
| 记忆 | T31 |
| 安全架构 | T07、T10b、T12b、T14a、T14b、T15a、T37 |
| API 设计 | T07、T09b、T15b、T17a、T17b、T19b、T26 |
| 计量与预算 | T09a、T16 |
| 可观测性 | T04、T06、T27 |
| 非功能需求 | R1、T29 |
| 部署架构 | T14a（compose）、T28 |
| 控制台 | T19b、T20a–T25b、R3 |
| 测试策略 | T01、T03、T09b、T29 |
| M0 技术验证 | T03–T13、R1 |
| M1 验收 | T29、R4 |
| M2、M3 能力 | T30–T42、R5、R6 |

## M0 验证项

| 编号 | 验证内容 | 负责任务 | 报告 |
|---|---|---|---|
| P1 | AgentScope 2.0.4 在 Boot 4.1.2、JDK 25 下运行 | T01、T03 | `docs/poc/P01-运行基线.md` |
| P2 | `FencedAgentStateStore` 与租约 | T05、T06 | `docs/poc/P02-租约与fencing.md` |
| P3 | 批次级 checkpoint | T06 | `docs/poc/P03-批次checkpoint.md` |
| P4 | 两个档位的文件系统装配 | T10a、T12a | `docs/poc/P04-文件系统装配.md` |
| P5 | agent-sandbox、gVisor、预热池 | T11 | `docs/poc/P05-沙箱与预热池.md` |
| P6 | `matchRule` 与审批流程 | T08、T10a、T12a | `docs/poc/P06-规则与审批.md` |
| P7 | 单 Pod 容量 | R1 | `docs/poc/P07-容量.md` |
| P8 | MCP Server 的 Servlet 传输 | T10b、T19 | `docs/poc/P08-MCP-Server.md` |
| P9 | 外部 `SandboxContext` 与会话沙箱 | T12a | `docs/poc/P09-会话沙箱.md` |
| P10 | 四种终止方式下的恢复语义 | T06 | `docs/poc/P10-恢复语义.md` |
| P11 | 状态缓存清理后的内存 | R1 | `docs/poc/P11-内存.md` |
| P12 | 子智能体并行与中断 | T13 | `docs/poc/P12-子智能体.md` |
| P13 | 中间件执行顺序与压缩守护 | T04、T09a | `docs/poc/P13-中间件顺序.md` |
| P14 | 出网代理按源 Pod IP 鉴权 | T12b | `docs/poc/P14-出网控制.md` |
| P15 | 无人审批执行的权限改写 | T08 | `docs/poc/P15-权限改写.md` |

## 当前任务检查点

进行中的任务在这里维护自己的检查清单，每完成一项就勾选并提交。任务完成时清空本节。

**T08 消息投递、待处理动作与权限合成**（开工基线：`scripts/dev/verify.sh` 通过，提交 8257c24）

- [x] 1. 权限合成：`PermissionSynthesizer`（五层、模式收紧、unattended 与子线程改写、`plan_enter` deny、子智能体策略）、平台安全规则、`PolicyStorePort` 与 `rt` 表实现、接入 `TurnPreparer`（A06 的单测部分）
- [ ] 2. 投递：中断（`interrupt_requested_at`、`node:{lease_owner}` 频道、续租兜底、`queued` → `cancelled`）、`awaiting_action` 下 steer 降级与中断拒绝全部动作、`SteerMiddleware`（A01、A02、A03）
- [ ] 3. 待处理动作：回应标记、恢复轮生成（先确认后工具结果、`recovery_confirmation` 的 retry/skip 与 D13）、超时调度（`SKIP LOCKED`）、unattended 兜底自动拒绝（A04、A05）
- [ ] 4. "记住此决定"会话级与项目级（A07）
- [ ] 5. 自定义工具：名称与保留名校验、挂起回传续跑（A08）
- [ ] 6. 审计 outbox：权限判定与动作回应（A09）
- [ ] 7. P15 报告、P10 steer 重跑与报告补全（A06、A10）
- [ ] 8. 收尾：`verify.sh`（A11）、模块 CLAUDE.md、P6/P15 状态、验收记录、看板与会话日志

## 控制台设计轨道

T20a、T20b、T20c 在独立的 worktree 与分支中执行，与后端任务并行。它们的进度只写在本节，不改上面的主看板与主检查点，避免合并冲突。

1. T07 在 `main` 上完成后，由你在主工作区执行：

   ```bash
   git worktree add ../agent-meridian-console -b console/T20 main
   cp .claude/settings.local.json ../agent-meridian-console/.claude/
   ```

2. 在 `../agent-meridian-console` 中开新会话，依次执行 `/task T20a`、`/task T20b`、`/task T20c`。T20a 会停在视觉方向关口，你看过截图并确认后，在同一 worktree 中再次输入 `/task T20a` 并附上确认意见。
3. T20c 完成后，回到主工作区合并并移除 worktree：

   ```bash
   git merge console/T20
   git worktree remove ../agent-meridian-console
   ```

4. 合并后才能开始 T21。

### 轨道看板

| 编号 | 任务 | 阶段 | 依赖 | effort | 规模 | 验证项 | 状态 | 完成提交 |
|---|---|---|---|---|---|---|---|---|
| T20a | [控制台设计方向：工程、令牌、图标与时间轴原型](tasks/T20a-console-direction-and-timeline.md) | 4 | T07（在独立 worktree 中与后端任务并行） | xhigh | 大 | 无 | 未开始 | — |
| T20b | [控制台基元与领域组件](tasks/T20b-console-components.md) | 4 | T20a（视觉方向已确认） | high | 大 | 无 | 未开始 | — |
| T20c | [控制台原型与设计评审](tasks/T20c-console-prototypes-and-review.md) | 4 | T20b | xhigh | 中 | 无 | 未开始 | — |

### 轨道检查点

（当前没有进行中的轨道任务）

### 轨道会话日志

（暂无记录）

## 登记事项

任务执行中发现、但不属于本任务范围的问题，在这里登记：编号、发现于哪个任务、内容、建议由哪个任务处理。

| 编号 | 发现于 | 内容 | 建议处理 |
|---|---|---|---|
| D01 | T01 | 架构规则未落地：`JdbcClient` 与 `JdbcTemplate` 只能被 `TenantScopedRepository`、`SystemRepository` 的子类依赖；`SystemRepository` 的子类只在 `..infra.system..` 且带 `@CrossTenant`；`..api..` 不依赖 `SystemRepository` 的子类。基类尚不存在，说明写在 `meridian-arch-tests` 的 `ArchitectureRulesTest` 类注释中 | T02（已落地：`ArchitectureRulesTest` 规则 8 至 11） |
| D02 | T01 | 架构规则未落地：平台工具继承 `ToolBase`（经 `PlatformTool`）；运行时 Toolkit 中的非 `ToolBase` 工具必须在白名单内（启动自检） | T10a |
| D03 | T01 | Spring Boot 4.1.2 BOM 把 fabric8 `kubernetes-httpclient-vertx`（Vert.x 4.5.28）下的 Netty 从 4.1.135 提升到 4.2.19；Vert.x 4.5 基于 Netty 4.1 构建，需要在真实调用中确认兼容，不兼容时按决策分级处理。详见 `docs/poc/P01-运行基线.md` | T03（已验证：fabric8 Vert.x 客户端在 Netty 4.2.19 上对本地 WebFlux 服务的真实 HTTP 请求正常）、T11（对 Kubernetes API 的调用） |
| D04 | T02 | outbox 发布到 Redis Stream（`usage`、`audit`）时不裁剪，条目在确认后仍留在 Stream 中；消费者确认后需按已确认的最小 ID 执行 `XTRIM MINID` 或定期裁剪，避免 Redis 内存增长 | T16 |
| D05 | T03 | `HarnessAgent` 不设置 `workspace(...)` 时，本机工作区根取 `${user.dir}/.agentscope/workspace`：harness 会把其中的 `skills/` 作为第 3 层技能读取，`MarketplaceStager` 会在其中暂存技能资源。平台要求技能只来自平台技能仓库、会话数据不落本机磁盘，装配文件系统与技能仓库时需要确定一个平台控制的本机工作区根目录（或证明无写入）。详见 `docs/poc/P01-运行基线.md` | T10a |
| D06 | T03 | JDK 25 把 `System.loadLibrary` 列为受限方法，reactor-netty 加载 Netty 本地传输库时告警"将来会被阻止"；runtime 的集成测试已加 `--enable-native-access=ALL-UNNAMED`，生产与本地启动的 JVM 参数同样需要。详见 `docs/poc/P01-运行基线.md` | T28 |
| D07 | T04 | `user.message` 中的文件引用（`MessageContent.FileRef`、按文件 ID 引用的图片）暂时翻译为文本占位 `[文件 fil_… 名称 (类型)]`，见 `TurnInputTranslator` 中的 `TODO(T17b)`；轮次开始时把文件放进工作区后改为工作区路径 | T17b |
| D08 | T04 | `PlatformContextMiddleware` 只在组装下游的同步阶段写 MDC（`tenant_id`、`session_id`、`turn_id`），跨线程的响应式链路没有 MDC；日志要求每行带 `trace_id`、`span_id`、`tenant_id`、`session_id`，需要 Reactor 自动上下文传播加 MDC 访问器的整体方案 | T27 |
| D09 | T04 | Worker 要处理 ADR-0026 的两条约定：`session.status_rescheduled` 的原因 `retryable_error` 让 `recovery_attempt` 加一（`node_shutdown` 不变）；`runTurn` 以 `LeaseLostException` 结束时放弃本轮、不写事件 | T06（已落地：`TurnFinisher.requeue` 按原因置回；`TurnExecution` 遇到租约丢失放弃本轮，`TurnRecoveryIT`、`WorkerLeaseFencingIT`） |
| D10 | T05 | ADR-0027 的锁顺序要求同一事务里先入队、再写事件；API 发送 `user.message` 时，轮次输入与收件箱需要事件 ID，`SessionEventWriter` 目前自己生成事件 ID，需改为允许调用方先生成、随批次传入 | T07（已落地：`SessionEventWriter.append(batch, eventIds)`，`EventIntake` 先生成事件 ID） |
| D11 | T05 | PostgreSQL 的 jsonb 拒绝字符串中的 `\u0000`：`agent_states.state`、`session_events.data` 都是 jsonb，模型输出或工具结果含 NUL 字符时写入会失败（状态保存失败、事件写不进）。需要在写入前统一处理（例如替换为 U+FFFD）并加测试 | T06（已落地：`JdbcValues.jsonb` 统一替换，`JdbcValuesTest`、`SessionEventWriterIT`） |
| D12 | T06 | 消息入箱与轮次结束必须按线程串行，否则消息会留在收件箱没有轮次处理：T07 写 `user.message` 的事务要先取 `ThreadLockRepository.lock(thread_id)`（`pg_advisory_xact_lock`），再入队或写收件箱，再写事件（ADR-0028 第 8 条） | T07（已落地：`EventIntake`、归档与删除的事务先取主线程咨询锁，`SessionApiIT`） |
| D13 | T06 | `recovery_confirmation` 的 `payload.recovery_mode = replay_input` 时状态里没有本轮的输入，回应后只生成 `RecoveryOnly` 轮会丢掉用户消息；需重放原轮的 `turn_inputs`（附恢复说明）。架构设计「待处理动作」写的是"一律生成 `RecoveryOnly` 轮"，T08 落地时按决策分级处理 | T08 |
| D14 | T06 | 恢复说明的结果摘要先取已持久化的工具结果事件，强杀发生在结果事件写库之前时只能取账本的 `result_ref`；平台工具基类在 `recordFinish` 时应写入结果引用（至少 `non_idempotent` 工具） | T10a |
| D15 | T06 | 节点下线最长等待 60 秒（`meridian.runtime.worker.shutdown-timeout`，AgentScope 在 45 秒时强制中断），Pod 的 `terminationGracePeriodSeconds` 必须大于它 | T28 |
| D16 | T07 | 回应事件（`user.tool_confirmation`、`user.custom_tool_result`、`user.recovery_confirmation`）与 `user.interrupt` 只校验并持久化：动作没有标为已回应（T08 之前同一动作可被再次回应，列表仍显示 pending），中断没有写 `interrupt_requested_at`；见 `EventIntake` 的 `TODO(T08)` | T08 |
| D17 | T07 | `traceparent` 只在响应中回显并写入请求日志的 MDC（`trace_id`），没有传到轮次执行：Worker 的 `turn` span 不是调用方追踪的子 span（ADR-0030 第 7 条） | T27 |
| D18 | T07 | `FileAgentConfigProvider` 不按项目隔离：任何项目的 API Key 都能用开发配置中的智能体与环境创建会话；`AgentConfigProvider` 新增的 `currentAgentVersion`、`currentEnvironment` 同样需要按项目解析 | T15b |
| D19 | T07 | SSE 读取 Redis Stream 用每节点一个 100 毫秒间隔的不阻塞 `XREAD` 轮询（ADR-0030 第 4 条），实时延迟上限与单节点 Redis 负载需要在容量验证中核对 | R1（P7） |
| D20 | T07 | Hibernate Validator 第一次校验失败时在 Netty 事件循环上读取消息资源包（每个进程一次，测试中由 BlockHound 放行）；需要时可在启动时预热 | T27 |

## 技术债

R4 及之后的评审汇总的技术债，按影响排序。

（暂无）

## 会话日志

每个任务收尾时在末尾追加一条，格式：

```text
### 2026-11-08 T01 已完成（提交 abc1234）
- 交付：……
- 验收：11 项通过，0 项阻塞
- 决策：ADR-00xx ……
- 遗留：……
```

### 2026-11-07 T01 已完成（提交 b29c1af）
- 交付：Maven Wrapper（3.9.16）、根 POM 与 14 个模块骨架、`meridian-bom`（Boot 4.1.2、AgentScope 2.0.4）、Spotless、Checkstyle、enforcer（默认拒绝的模块依赖规则）、Surefire、Failsafe、JaCoCo、`live` profile、`meridian-arch-tests` 7 条规则、`scripts/dev/verify.sh`、`scripts/ci/check-pom-versions.sh`、CI、根 `CLAUDE.md` 命令一节、P1 依赖解析报告
- 验收：11 项通过，0 项阻塞（T01-A01 初次为阻塞：`~/.zshrc` 中 `JAVA_HOME` 缺少 `/Contents/Home`；经用户授权修正后 `java -version` 与 `./mvnw -v` 均为 25）
- 决策：ADR-0022 构建门禁与依赖规则的落地方式
- 遗留：登记事项 D01（T02）、D02（T10a）、D03 Netty 4.2 与 Vert.x 4.5 兼容性（T03、T11）

### 2026-11-07 T02 已完成（提交 4812ea7）
- 交付：`meridian-common`（id、error、page、json、time、context、concurrent）；`meridian-persistence`（TenantScopedRepository/SystemRepository/@CrossTenant、outbox、Redis 阻塞与响应式封装、ObjectStore、Idempotency、迁移约定与自动配置）；`meridian-security` 的 `web` 包（请求 ID、problem+json，MVC 与 WebFlux 各一个适配器）；`meridian-test-support`（单例容器、TestDatabase、SpringContainerProperties）；PostgreSQL 17 镜像（pgvector 0.8.6 + SCWS 1.2.3 + zhparser 2.3）、compose、up.sh/down.sh；ArchUnit 规则 8–11；四个模块 CLAUDE.md
- 验收：11 项通过，0 项阻塞
- 决策：ADR-0020 持久化与上下文传递（含 type URN、游标校验和、幂等作用域、MinIO 改用 Chainguard 镜像、test-support 不依赖 persistence）
- 遗留：登记事项 D04 Redis Stream 裁剪（T16）

### 2026-11-07 T03 已完成（提交 6038ef8）
- 交付：`meridian-engine-spi` 全部契约（engine、engine.input、event、spec、port、driver，含新增端口 `AgentConfigPort`）；`meridian-engine-agentscope` 的 `assembly`（`AgentAssembler` 按装配表配置 `HarnessAgent`、`AgentInstanceCache`/`RefCountedCache` 基于 Caffeine 的引用计数缓存、`NoopMessageBus`、`NoopAsyncToolRegistry`）；`meridian-engine-agentscope-test`（`ScriptedModel` 与 DSL、8 个内存端口、装配替身、`RecordingTool`）；runtime 引入 WebFlux 与 P1 集成测试；ArchUnit 规则 12；三个模块 CLAUDE.md；P1 报告
- 验收：8 项通过，0 项阻塞
- 决策：ADR-0023 `maxRetries(1)`（变更型，用户采纳）；ADR-0024 引擎装配与内核契约的补充约定；ADR-0025 testkit 可依赖 common 与 engine-spi（变更型，用户采纳）；架构设计升至 v1.2
- 遗留：登记事项 D05 本机工作区根目录（T10a）；D03 对 Kubernetes API 的调用待 T11；D06 生产 JVM 需 `--enable-native-access=ALL-UNNAMED`（T28）

### 2026-11-07 T04 已完成（提交 0943c89）
- 交付：`meridian-engine-agentscope` 的 `turn.AgentScopeEngine`（runTurn、interrupt、shutdown；输入与权限翻译、自定义工具注入、取消安全的资源归还）、`events`（`TurnEventMapper` 增量拼装与终态保证、穷举的 `AgentEventDisposition` 与 `StopReasonMapping`）、`state.FencedAgentStateStore`、`middleware`（`PlatformContextMiddleware`、`PlatformTracingMiddleware`、`LeaseGuardMiddleware`、`ToolSpanMiddleware`、`PlatformStopRequest`、`PlatformMiddlewareProvider`）；`meridian-engine-spi` 新增 `error` 包与 `RescheduleReason.RETRYABLE_ERROR`；engine-agentscope-test 新增 51 个测试（场景、映射、状态存储、中间件链与 span、下线、翻译），engine-spi 新增 2 个与 `opentelemetry-sdk-testing`（test）；P13 进行中
- 验收：10 项通过，0 项阻塞
- 决策：ADR-0026 单轮执行与事件映射的补充约定（可重试错误以 `rescheduled(retryable_error)` 结束、租约丢失不输出事件、工具调用在模型调用结束时输出等）；架构设计升至 v1.3
- 遗留：登记事项 D07 文件引用（T17b）、D08 响应式链路的 MDC（T27）、D09 Worker 处理 ADR-0026 的约定（T06）

### 2026-11-07 T05 已完成（提交 c1fd533）
- 交付：runtime 应用骨架（`RuntimeApplication`、`dev`/`test` profile、actuator 健康检查与探针、`scripts/dev/run-runtime.sh`）；`V0001__runtime_core.sql` 建出 runtime 核心表全部（`session_events` 16 个哈希分区、`turns` 部分唯一索引、中文注释）；`dispatch`（入队、三步认领、续租、回收、置回队列三种原因、结束轮次、唤醒、`NodeLeaseRegistry`）；`stream`（`SessionEventWriter` 唯一写入入口、`EngineJson`、`DatabaseEventSink`）；`session`（会话与线程仓储、`JdbcAgentStateRepository`）；runtime 测试经 `blockhound-junit-platform` 自动开启 BlockHound；64 个单元测试、63 个新集成测试；模块 CLAUDE.md；P2 存储层报告
- 验收：10 项通过，0 项阻塞
- 决策：ADR-0027 运行时数据层与调度的补充约定（时间取注入的 `Clock`、同节点重新认领的落地方式、`FOR SHARE` 租约条件与锁顺序、事件与输入的 JSON 约定、状态随事件推进、默认并发上限 20 等）；架构设计升至 v1.4
- 遗留：登记事项 D10 调用方先生成事件 ID（T07）、D11 jsonb 拒绝 `\u0000`（T06）；P2 完整路径待 T06

### 2026-11-07 T06 已完成（提交 c6ca61a）
- 交付：runtime `session.app`（`TurnWorker`/`TurnExecution` 执行一轮、`TurnPreparer` 恢复认领先判定、`TurnFinisher` 结束事务与合并收件箱、`TurnWorkerLifecycle` 后台循环与优雅下线、`TurnMetrics` 六个指标）、`session.domain.RecoveryPlanner`；`delivery`（`InboxPort`）、`tooling`（`ToolLedgerPort`）、`approval`（待处理动作写入，含 `recovery_confirmation`）；`stream`（提交后 XADD `ev:{session_id}`、`ObjectStoreBlobStore`）；`config`（`FileAgentConfigProvider` 读 `dev-config/agents`、`EngineAutoConfiguration`）；迁移 `V0002`（`content_turn_id`）；engine 的 `RecoveryNoteMiddleware` 与 `BatchCheckpointMiddleware`；persistence 的 jsonb 去 NUL、有界 XADD、`PeriodicJob` 阶段；故障注入、P2 完整路径、P3、端口与装配的测试；P02 定稿、P03、P10 报告
- 验收：10 项通过，0 项阻塞
- 决策：ADR-0028（崩溃恢复按本轮的内容写入判定、恢复认领按 epoch、批次 checkpoint 默认开启、结束事务与线程咨询锁、事件扇出约定、内核装配为自动配置等）；架构设计升至 v1.5
- 遗留：登记事项 D12 线程咨询锁（T07）、D13 恢复确认在 replay 模式下重放输入（T08）、D14 平台工具记账写结果引用（T10a）、D15 下线宽限期（T28）；`TurnPreparer` 的权限策略 `TODO(T08)`；steer 的验证待 T08 的 `SteerMiddleware` 重跑

### 2026-11-07 T07 已完成（提交 708f7ca）
- 交付：`meridian-api` 的公开 API 规范 `meridian-v1.yaml`（OpenAPI 3.1：12 个操作、26 种持久化事件与 3 种流上事件、problem+json）与 DTO 生成，`OpenApiSpecTest`、`OpenApiCompatibilityTest`（openapi-diff + 3.1 类型变化检查）；runtime 生成 reactive 接口并实现会话（创建固化版本、查询、列表、更新、归档、删除）、发送与列出事件（`EventIntake`：咨询锁、组合规则、入队或收件箱）、线程、待处理动作、会话流与线程流（`SessionStreamHub` 共用 XREAD 轮询 + `EventStreamService` 五步算法、两个指标）；幂等 `IdempotentRequests`、迁移 `V0003`（`deleted_at`）；`meridian-security` 的 `apikey` 包（`ApiKeyResolver`、`DevApiKeyResolver`、`ApiKeyWebFilter`、作用域检查）与 `traceparent` 过滤器；契约测试支撑与 9 个新集成测试类；`meridian-api` 的 CLAUDE.md
- 验收：12 项通过，0 项阻塞
- 决策：ADR-0029 OpenAPI 生成链与 3.1 写作约束（openapi-generator 7.26.0、判别器分支平铺加 const、openapi-diff 的 3.1 缺口与补充检查）；ADR-0030 会话 API 与 SSE 的补充约定（删除返回 202、归档条件、批量事件、SSE 读取方式与线程流的位置判断、幂等指纹、开发期认证）；架构设计升至 v1.6
- 遗留：登记事项 D16 回应与中断的语义（T08）、D17 `traceparent` 传播（T27）、D18 智能体配置按项目隔离（T15b）、D19 SSE 轮询的容量核对（R1）、D20 校验资源包预热（T27）

## 计划变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-11-07 | v1.0 | 初版：48 个任务（T01–T42、R1–R6），370 个验收项 |
| 2026-11-07 | v1.1 | 对抗评审后修订：拆分 T09、T10、T12、T14、T15、T17、T20、T25 为带字母后缀的子任务，新增 T19b（控制台所需的后端接口），R2 回到纯评审；T20a 增加视觉方向关口，控制台设计轨道改在 worktree 中并行；T31、T32、T34、T35、T37 增加设计关口；补上租户并发配额、脚本化模型服务、提示词分层与来源标签、docx 内置技能、事件保留、知识库计量等无人负责的条目；前端验收改为可量化标准。共 58 个任务、417 个验收项 |
