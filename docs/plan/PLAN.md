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
| 1 M0 内核与运行时核心 | 引擎契约与适配、调度与租约、恢复、会话 API 与 SSE、投递与权限、模型层与脚本化模型服务、工具、沙箱与出网、子智能体；M0 验证 P1–P15 | T03–T13 | R1 | 有条件完成：进入 M1。前提（都不阻塞 T14a）：Q2 已决（ADR-0043）；Q3 已决、D52 已落地（T15a，ADR-0045、ADR-0046）；T28 落实 D44–D46 并由 T29 复测 P7；P8 已由 T19 定稿（`docs/poc/M0-总结.md`） |
| 2 M1 控制面与治理 | 租户与 API Key、OIDC 与服务 JWT、凭据与模型目录、智能体与配置分发、计量预算审计、运行时补全 | T14a–T17b | — | 进行中 |
| 3 M1 知识库与后端收口 | 写入、检索与接入、控制台所需的后端接口 | T18–T19b | R2 | 已完成：M1 后端完整，进入控制台阶段。控制台可依赖的接口清单是 `docs/engineering/console-api-map.md`；评审结论与登记事项 D86–D94 见 `docs/plan/R2-后端评审.md`（D86 API Key 最后使用时间需要你决定是否插入补充任务） |
| 4 M1 控制台 | 设计方向与组件、原型（并行轨道）、工程基座、五组页面 | T20a–T25b | R3 | 进行中 |
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
| T08 | [消息投递、待处理动作与权限合成](tasks/T08-delivery-actions-permissions.md) | 1 | T07 | xhigh | 大 | P6（审批流程部分）、P15 | 已完成 | f75bc4f |
| T09a | [模型层：RoutedModel、限流、预算、计量与压缩守护](tasks/T09a-routed-model-budget-compaction.md) | 1 | T08 | xhigh | 大 | P13 | 已完成 | 5e8b21a |
| T09b | [模型直通接口、Embedding 与 rerank、脚本化模型服务](tasks/T09b-model-endpoints-and-scripted-server.md) | 1 | T09a | high | 中 | 无 | 已完成 | 003dbfc |
| T10a | [平台工具基类、文件工具、工作区存储与技能](tasks/T10a-tools-files-workspace-skills.md) | 1 | T09b | high | 大 | P4（`none` 档）、P6（文件工具规则） | 已完成 | b724148 |
| T10b | [Web 工具、知识检索工具、MCP 客户端与提示词分层](tasks/T10b-web-knowledge-mcp-prompts.md) | 1 | T10a | high | 大 | P8（预验证） | 已完成 | 7e490f5 |
| T11 | [沙箱运行时镜像、集群与驱动](tasks/T11-sandbox-runtime-and-driver.md) | 1 | T10b | high | 大 | P5 | 已完成 | bcd2246 |
| T12a | [会话沙箱生命周期、bash 与产物](tasks/T12a-session-sandbox-and-bash.md) | 1 | T11 | xhigh | 大 | P4（`sandbox` 档）、P6（bash 规则）、P9 | 已完成 | b3bdef7 |
| T12b | [沙箱出网控制：Envoy、ext_authz 与 NetworkPolicy](tasks/T12b-sandbox-egress-control.md) | 1 | T12a | xhigh | 中 | P14 | 已完成 | 086b6f4 |
| T13 | [子智能体](tasks/T13-subagents.md) | 1 | T12b | xhigh | 中 | P12 | 已完成 | e5621ba |
| R1 | [M0 评审与收口](tasks/R1-m0-review.md) | 1 | T13 | high | 中 | P7、P11，以及 P1 至 P15 的汇总 | 已完成 | 34b708c |
| T14a | [control-plane 基础：租户、成员、API Key 与限流](tasks/T14a-control-plane-tenancy-apikeys.md) | 2 | R1（结论为进入 M1） | high | 大 | 无 | 已完成 | 251ed4e |
| T14b | [身份：OIDC、Keycloak 与服务 JWT](tasks/T14b-oidc-and-service-jwt.md) | 2 | T14a | high | 中 | 无 | 已完成 | ed57d00 |
| T15a | [凭据库、供应商、模型目录与价格表](tasks/T15a-secrets-providers-models.md) | 2 | T14b | high | 大 | 无 | 已完成 | a9db160 |
| T15b | [智能体版本、环境、技能、MCP 与配置分发](tasks/T15b-agents-environments-config.md) | 2 | T15a | high | 大 | 无 | 已完成 | 4c867bf |
| T16 | [计量、预算与审计](tasks/T16-metering-budget-audit.md) | 2 | T15b | high | 中 | 无 | 已完成 | f5de788 |
| T17a | [运行时补全：Webhook、会话删除、事件归档与保留](tasks/T17a-webhooks-deletion-retention.md) | 2 | T16 | high | 中 | 无 | 已完成 | 9aa41da |
| T17b | [运行时补全：文件、工作区、用量事件与 Responses](tasks/T17b-files-workspace-responses.md) | 2 | T17a | high | 中 | 无 | 已完成 | b7df25f |
| T18 | [知识库写入](tasks/T18-knowledge-ingestion.md) | 3 | T17b | high | 大 | 无 | 已完成 | 51dc16a |
| T19 | [知识库检索与接入](tasks/T19-knowledge-retrieval.md) | 3 | T18 | high | 中 | P8（定稿） | 已完成 | c87dc78 |
| T19b | [控制台所需的后端接口](tasks/T19b-console-backend-apis.md) | 3 | T19 | high | 大 | 无 | 已完成 | 695b7e3 |
| R2 | [M1 后端评审](tasks/R2-backend-review.md) | 3 | T19b | high | 中 | 无 | 已完成 | a6a2c04 |
| T20a | [控制台设计方向：工程、令牌、图标与时间轴原型](tasks/T20a-console-direction-and-timeline.md) | 4 | T07（在独立 worktree 中与后端任务并行） | xhigh | 大 | 无 | 见控制台设计轨道 | — |
| T20b | [控制台基元与领域组件](tasks/T20b-console-components.md) | 4 | T20a（视觉方向已确认） | high | 大 | 无 | 见控制台设计轨道 | — |
| T20c | [控制台原型与设计评审](tasks/T20c-console-prototypes-and-review.md) | 4 | T20b | xhigh | 中 | 无 | 见控制台设计轨道 | — |
| T21 | [控制台工程基座](tasks/T21-console-foundation.md) | 4 | T20c（`console/T20` 已合并回 `main`）、R2 | high | 大 | 无 | 已完成 | 5220288 |
| T22 | [控制台：智能体与环境](tasks/T22-console-agents-environments.md) | 4 | T21 | high | 大 | 无 | 已完成 | 1cf2761 |
| T23 | [控制台：能力、模型与凭据](tasks/T23-console-capabilities-models-secrets.md) | 4 | T22 | high | 中 | 无 | 已完成 | 4b1fad4 |
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

（当前没有进行中的任务）

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
| T20a | [控制台设计方向：工程、令牌、图标与时间轴原型](tasks/T20a-console-direction-and-timeline.md) | 4 | T07（在独立 worktree 中与后端任务并行） | xhigh | 大 | 无 | 已完成 | 2d99b8f |
| T20b | [控制台基元与领域组件](tasks/T20b-console-components.md) | 4 | T20a（视觉方向已确认） | high | 大 | 无 | 已完成 | 3967cc5 |
| T20c | [控制台原型与设计评审](tasks/T20c-console-prototypes-and-review.md) | 4 | T20b | xhigh | 中 | 无 | 已完成 | 0ff211e |

### 轨道检查点

（当前没有进行中的轨道任务）

### 轨道会话日志

### 2026-11-13 T20a 阻塞：等待用户确认视觉方向（worktree `../agent-meridian-console`，分支 `console/T20`）
- 交付：`console/` 工程（Vite 8、React 19、TypeScript 6 严格、pnpm 10、ESLint 10 与本地守护规则、Prettier、Vitest、Storybook 10、MSW 3、Playwright、openapi-typescript）；令牌（两套 OKLCH 调色板、`@theme inline` 映射、默认主题全部移除）与令牌页；字体（Plex Sans、Mono 与 Plex Sans SC 官方切片）；图标（Phosphor 封装与清单，品牌标识与七个领域图标）；六个基元；`SessionTimeline`（模型纯函数、子午线与刻度、子线程分叉并回、此刻、落位动画、虚拟滚动、键盘、行内批准）；SSE 客户端；会话详情原型与应用外壳原型（MSW 模拟会话接口与 SSE）；Playwright 性能、动效、无障碍与评审截图；`verify.sh` 接入；`console/CLAUDE.md`
- 验收：A01–A06、A08 通过；A09 通过（全量 verify.sh，见收尾提交）；A07 等待用户确认
- 决策：ADR-0021（前端依赖与版本：TypeScript 6.0.3、ESLint 10.14.0、pnpm 10.34.9、Plex Sans SC 官方切片、自写导入边界规则）；令牌不用 `light-dark()`（构建降级后嵌套主题失效）；时间轴帧时间按 Chrome 跟踪的每帧主线程忙碌时间衡量
- 遗留：工作台三栏的拖动调宽、无权限说明（`PermissionGate`）、`DangerConfirmDialog` 留给 T20b、T20c；主工作区未同步本分支，T20c 后由用户合并

### 2026-11-13 T20a 已完成（提交 2d99b8f）
- 交付：用户确认视觉方向（"看过了整体没什么问题"，无修改意见），A07 通过；`frontend-design.md`「设计定稿」与根 `CLAUDE.md` 标明方向已确认
- 验收：9 项通过，0 项阻塞
- 决策：应用户要求，T20a 完成后即把 `console/T20` 合并回 `main`（原计划在 T20c 之后合并）；T20b、T20c 可在主工作区继续，进度仍写在本节
- 遗留：同上一条；全量 `verify.sh` 在 2d99b8f 上通过，本次只改文档

### 2026-11-13 T20b 已完成（提交 3967cc5；主工作区 `main`，用户要求不建 worktree）
- 交付：shadcn CLI 4.23.0 在会话临时目录生成并按令牌重塑的 17 个基元（输入框、文本域、标签、选择器、复选框、单选、开关、对话框、警示对话框、抽屉、下拉菜单、弹出层、表格、分隔线、面包屑、命令面板 cmdk、通知 Sonner），T20a 的 6 个基元补上故事；「自研组件」表中其余 18 个领域组件（StatusBadge 与统一状态映射、EventCard、ToolCallView、ThreadTree、InspectorPanel、TokenUsage、CopyableId、RelativeTime、EmptyState、ErrorState、PermissionGate、DangerConfirmDialog、PageHeader、FilterBar 与 URL 同步、DataTable 与虚拟滚动、KeyValueList、CodeBlock 与 Shiki、StreamIndicator）；三张空态插画；新令牌 `scrim` 与四种语法色、浮层退出与抽屉滑入的关键帧、`unavailable:` 变体；`InertOutside`；每个状态一个故事（pseudo-states 插件）与基元、领域组件总览；a11y 用例改为读 `index.json` 检查全部故事；`evidence-components.spec.ts`；会话列表夹具；依赖追加到 ADR-0021
- 验收：A01–A05 全部 passing（a11y 亮暗各 217 个故事零违规、领域组件 95 个用例、全量 164 个用例、`verify.sh` 通过，截图在 `tmp/evidence/T20b/`）
- 决策：没有新 ADR（只追加 ADR-0021 的版本表与三点说明：TanStack Table 9、shadcn 在临时目录生成、语法色走令牌）；开关做成 4px 圆角的拨动开关；复选框与单选的记号是控件自身的形状；PermissionGate 用 aria-disabled 保持可聚焦；模态浮层外加 inert；命令面板的空结果放在 listbox 之外；可滚动区域可聚焦（jsx-a11y 放行 role=region 带 tabIndex）；给人读的工具结果用正文字体；Vitest 固定东八区
- 遗留：会话详情原型的头部、检查器、线程栏仍是 T20a 的内联实现，T20c 换成领域组件；FilterBar 的 URL 同步用 History API，T21 换成 TanStack Router 的搜索参数；PermissionGate 只接收 `allowed` 与 `reason`，由 T21 按 `/admin/v1/me/access` 计算

### 2026-11-13 T20c 已完成（提交 0ff211e；主工作区 `main`，沿用 T20b 不建 worktree）
- 交付：Storybook「原型」组 42 个故事——应用外壳（导航收起并记在本地、项目切换、⌘K 命令面板、预算横幅）、四种页面模板的布局组件与故事（`ListPage`、`DetailPage`、`EditPage`、`Workbench` 三栏可调宽，分别配智能体列表、智能体详情、环境编辑、会话详情）、会话列表、待处理（待回应与历史、详情抽屉）、智能体编辑（八个分组、校验、发布确认、只读）、概览（运行状况、用量趋势、本月预算、按智能体的失败率与最近错误）；会话详情换成领域组件（T20b 遗留）；新领域组件 `ApprovalForm`；MSW 项目级处理器与"华东经营分析"夹具；管理数据的视图模型；`e2e/evidence-prototypes.spec.ts`；a11y 用例登记原型故事并检查整页
- 修正 T20b 的两处缺陷：超时的待处理动作显示为"已失效"（`actionStatus` 先判 expired）、选择器触发器一直是只读外观（`:read-only` 匹配按钮）
- 验收：A01–A04 全部 passing（两轮评审截图 `tmp/evidence/T20c/round1`、`round2`，a11y 223 个故事亮暗零违规、原型 62 passed，`verify.sh` 通过）
- 决策：没有新 ADR；ECharts 6.1.0 追加到 ADR-0021；评审记录写进 frontend-design.md「T20c 原型与评审」
- 遗留：工作区主线已包含 `console/T20`（T20a 后已合并），`git merge console/T20` 为空操作，只需移除 worktree；原型里的筛选在组件状态、表单是受控状态，T21、T22 换成路由搜索参数与 React Hook Form

## 登记事项

任务执行中发现、但不属于本任务范围的问题，在这里登记：编号、发现于哪个任务、内容、建议由哪个任务处理。

| 编号 | 发现于 | 内容 | 建议处理 |
|---|---|---|---|
| D01 | T01 | 架构规则未落地：`JdbcClient` 与 `JdbcTemplate` 只能被 `TenantScopedRepository`、`SystemRepository` 的子类依赖；`SystemRepository` 的子类只在 `..infra.system..` 且带 `@CrossTenant`；`..api..` 不依赖 `SystemRepository` 的子类。基类尚不存在，说明写在 `meridian-arch-tests` 的 `ArchitectureRulesTest` 类注释中 | T02（已落地：`ArchitectureRulesTest` 规则 8 至 11） |
| D02 | T01 | 架构规则未落地：平台工具继承 `ToolBase`（经 `PlatformTool`）；运行时 Toolkit 中的非 `ToolBase` 工具必须在白名单内（启动自检） | T10a（已落地：ArchUnit 规则 13；`ToolPolicy.verify` 每次装配检查，`EngineAutoConfiguration` 启动时探测装配一次，`ToolkitSelfCheckStartupIT`） |
| D03 | T01 | Spring Boot 4.1.2 BOM 把 fabric8 `kubernetes-httpclient-vertx`（Vert.x 4.5.28）下的 Netty 从 4.1.135 提升到 4.2.19；Vert.x 4.5 基于 Netty 4.1 构建，需要在真实调用中确认兼容，不兼容时按决策分级处理。详见 `docs/poc/P01-运行基线.md` | T03（已验证：fabric8 Vert.x 客户端在 Netty 4.2.19 上对本地 WebFlux 服务的真实 HTTP 请求正常）、T11（已验证：对 kind 集群 Kubernetes API 的创建、watch、合并补丁、删除与端口转发正常，P5） |
| D04 | T02 | outbox 发布到 Redis Stream（`usage`、`audit`）时不裁剪，条目在确认后仍留在 Stream 中；消费者确认后需按已确认的最小 ID 执行 `XTRIM MINID` 或定期裁剪，避免 Redis 内存增长 | T16（已落地：`RedisMessaging.trimAcknowledged` 按各消费组最小待确认 ID 执行 `XTRIM MINID`，`StreamConsumer` 每分钟裁剪，`StreamConsumerIT`） |
| D05 | T03 | `HarnessAgent` 不设置 `workspace(...)` 时，本机工作区根取 `${user.dir}/.agentscope/workspace`：harness 会把其中的 `skills/` 作为第 3 层技能读取，`MarketplaceStager` 会在其中暂存技能资源。平台要求技能只来自平台技能仓库、会话数据不落本机磁盘，装配文件系统与技能仓库时需要确定一个平台控制的本机工作区根目录（或证明无写入）。详见 `docs/poc/P01-运行基线.md` | T10a（已落地：`workspace(...)` 设为平台控制、不写入的本机目录；技能仓库继承 `WorkspaceSkillRepository` 不被暂存；P4 测试断言本机无写入，ADR-0034） |
| D06 | T03 | JDK 25 把 `System.loadLibrary` 列为受限方法，reactor-netty 加载 Netty 本地传输库时告警"将来会被阻止"；runtime 的集成测试已加 `--enable-native-access=ALL-UNNAMED`，生产与本地启动的 JVM 参数同样需要。详见 `docs/poc/P01-运行基线.md` | T28 |
| D07 | T04 | `user.message` 中的文件引用（`MessageContent.FileRef`、按文件 ID 引用的图片）暂时翻译为文本占位 `[文件 fil_… 名称 (类型)]`，见 `TurnInputTranslator` 中的 `TODO(T17b)`；轮次开始时把文件放进工作区后改为工作区路径 | T17b（已落地：轮次开始时放进工作区的 `uploads/{file_id}/{filename}`，翻译为工作区路径，ADR-0050） |
| D08 | T04 | `PlatformContextMiddleware` 只在组装下游的同步阶段写 MDC（`tenant_id`、`session_id`、`turn_id`），跨线程的响应式链路没有 MDC；日志要求每行带 `trace_id`、`span_id`、`tenant_id`、`session_id`，需要 Reactor 自动上下文传播加 MDC 访问器的整体方案 | T27 |
| D09 | T04 | Worker 要处理 ADR-0026 的两条约定：`session.status_rescheduled` 的原因 `retryable_error` 让 `recovery_attempt` 加一（`node_shutdown` 不变）；`runTurn` 以 `LeaseLostException` 结束时放弃本轮、不写事件 | T06（已落地：`TurnFinisher.requeue` 按原因置回；`TurnExecution` 遇到租约丢失放弃本轮，`TurnRecoveryIT`、`WorkerLeaseFencingIT`） |
| D10 | T05 | ADR-0027 的锁顺序要求同一事务里先入队、再写事件；API 发送 `user.message` 时，轮次输入与收件箱需要事件 ID，`SessionEventWriter` 目前自己生成事件 ID，需改为允许调用方先生成、随批次传入 | T07（已落地：`SessionEventWriter.append(batch, eventIds)`，`EventIntake` 先生成事件 ID） |
| D11 | T05 | PostgreSQL 的 jsonb 拒绝字符串中的 `\u0000`：`agent_states.state`、`session_events.data` 都是 jsonb，模型输出或工具结果含 NUL 字符时写入会失败（状态保存失败、事件写不进）。需要在写入前统一处理（例如替换为 U+FFFD）并加测试 | T06（已落地：`JdbcValues.jsonb` 统一替换，`JdbcValuesTest`、`SessionEventWriterIT`） |
| D12 | T06 | 消息入箱与轮次结束必须按线程串行，否则消息会留在收件箱没有轮次处理：T07 写 `user.message` 的事务要先取 `ThreadLockRepository.lock(thread_id)`（`pg_advisory_xact_lock`），再入队或写收件箱，再写事件（ADR-0028 第 8 条） | T07（已落地：`EventIntake`、归档与删除的事务先取主线程咨询锁，`SessionApiIT`） |
| D13 | T06 | `recovery_confirmation` 的 `payload.recovery_mode = replay_input` 时状态里没有本轮的输入，回应后只生成 `RecoveryOnly` 轮会丢掉用户消息；需重放原轮的 `turn_inputs`（附恢复说明）。架构设计「待处理动作」写的是"一律生成 `RecoveryOnly` 轮"，T08 落地时按决策分级处理 | T08（已落地：`replay_input` 时恢复轮重放原轮输入并附说明，ADR-0031 第 8 条，`RecoveryConfirmationIT`） |
| D14 | T06 | 恢复说明的结果摘要先取已持久化的工具结果事件，强杀发生在结果事件写库之前时只能取账本的 `result_ref`；平台工具基类在 `recordFinish` 时应写入结果引用（至少 `non_idempotent` 工具） | T10a（已落地：`PlatformTool` 把 `non_idempotent` 工具的结果写入对象存储、引用进账本，`PlatformToolLedgerTest`） |
| D15 | T06 | 节点下线最长等待 60 秒（`meridian.runtime.worker.shutdown-timeout`，AgentScope 在 45 秒时强制中断），Pod 的 `terminationGracePeriodSeconds` 必须大于它 | T28 |
| D16 | T07 | 回应事件（`user.tool_confirmation`、`user.custom_tool_result`、`user.recovery_confirmation`）与 `user.interrupt` 只校验并持久化：动作没有标为已回应（T08 之前同一动作可被再次回应，列表仍显示 pending），中断没有写 `interrupt_requested_at`；见 `EventIntake` 的 `TODO(T08)` | T08（已落地：回应标为已回应并在全部回应后生成恢复轮，中断按线程状态处理，`DeliveryMatrixIT`、`ApprovalFlowIT`） |
| D17 | T07 | `traceparent` 只在响应中回显并写入请求日志的 MDC（`trace_id`），没有传到轮次执行：Worker 的 `turn` span 不是调用方追踪的子 span（ADR-0030 第 7 条） | T27 |
| D18 | T07 | `FileAgentConfigProvider` 不按项目隔离：任何项目的 API Key 都能用开发配置中的智能体与环境创建会话；`AgentConfigProvider` 新增的 `currentAgentVersion`、`currentEnvironment` 同样需要按项目解析 | T15b（已落地：`ControlPlaneAgentConfigProvider` 在绑定了 `PlatformContext` 时要求智能体、版本、环境属于该项目，否则与不存在相同；`file` 来源只允许 dev、test profile，ADR-0047，`AgentConfigSessionIT`） |
| D19 | T07 | SSE 读取 Redis Stream 用每节点一个 100 毫秒间隔的不阻塞 `XREAD` 轮询（ADR-0030 第 4 条），实时延迟上限与单节点 Redis 负载需要在容量验证中核对 | R1（已核对，关闭：推送延迟 p50 约 60 至 90 毫秒、连接池不排队时 p99 105 至 193 毫秒，Redis CPU 5% 以内，P7） |
| D20 | T07 | Hibernate Validator 第一次校验失败时在 Netty 事件循环上读取消息资源包（每个进程一次，测试中由 BlockHound 放行）；需要时可在启动时预热 | T27 |
| D21 | T08 | "记住此决定"记的是整工具规则（批准 allow、拒绝 deny）：确认 `bash(git status)` 后记住会放行整个 `bash`（仍受 deny、ask 规则约束）。平台工具实现 `generateSuggestions` 后，内核应随 `tool_confirmation` 动作带出工具建议的内容级规则（命令前缀、路径），平台按它保存 | T10a 部分完成：`PlatformTool.generateSuggestions` 给出内容级建议（文件工具为路径，`bash` 为每段的命令前缀）。T12a 部分完成：`bash` 改为记住这一条命令（规则内容为命令原文，ADR-0037），不再放行整个工具。T19b 已落地剩余部分：内核按工具重新计算建议规则（2.0.4 的 `ReActAgent` 丢掉了 `PermissionDecision` 上的建议），随动作进入 `session.status_requires_action` 与 `pending_actions`，回应时 `remember_rule` 选一条记住（ADR-0053，`BashToolScenarioTest`、`FileToolScenarioTest`、`ConsoleQueriesIT`） |
| D22 | T08 | 租户与项目策略的开发实现在 `rt.permission_rules`，租户层没有写入接口；项目级"记住此决定"写入 runtime。平台安全规则使权限上下文永远不平凡，`DEFAULT` 模式下没有命中 allow 的工具都要确认：智能体版本的默认配置要显式放行工具（`unattended` 会话中的 `BYPASS` 智能体同样变为 `DONT_ASK`） | T15b（已落地：租户与项目策略在 `cp.permission_rules`，两层管理 API，"记住此决定"经内部接口写入 control-plane；runtime 的生产实现 `ControlPlanePolicyStore`，`DatabasePolicyStore` 只在 `agent-config.source=file`；工具放行由版本规格的 `permission_policy` 显式给出，ADR-0047，`AgentResourcesIT`、`AgentConfigSessionIT`） |
| D23 | T09a | 开发期的模型目录（`dev-config/providers`）与凭据解析（`EnvironmentSecretResolver`）不按项目授权：任何项目的智能体都能使用目录中的模型；`ModelGateway.resolve` 的"未授权给该项目"由 control-plane 的目录实现 | T15a（已落地：模型属于租户、租户内全部项目可用，别的租户的模型与不存在相同；凭据解析校验租户与项目；文件实现只允许 dev、test profile，ADR-0045，`ControlPlaneCatalogIT`） |
| D24 | T09a | 预算以 token（输入加输出）计，Redis 计数不分周期；软阈值告警、按价格表换算金额、预扣记录过期（1 小时）后未结算部分的修正、四级计数与账本的每小时对账都未实现（ADR-0032 第 6 条） | T16（已落地：仍以 token 计，租户、项目、API Key 按自然月、会话不分周期；软阈值告警；成本按价格表版本记在账本；对账以账本加未过期预扣为目标，过期未结算的预扣随之修正，ADR-0048） |
| D25 | T09a | `ModelGateway.Provider` 中只有 OpenAI 兼容类型有底层客户端（DashScope 走 `compatible-mode/v1`）；Anthropic、Gemini 的 2.0.4 模型类需要引入 `anthropic-java`、`google-genai`，DashScope 原生与 Ollama 同样未接入，扩展点是 `ProviderClientFactory`。供应商账号管理只能开放已实现的类型 | T15a（已落地：Q3 定为 OpenAI 兼容、Anthropic、Gemini；后两者用平台适配器，ADR-0046，`ProviderAdaptersIT`；DashScope 原生与 Ollama 原生不做，私有化推理服务走 OpenAI 兼容端点） |
| D26 | T09b | `/v1/chat/completions` 只支持文本消息与函数工具（ADR-0033）：`content` 为内容块数组、`tool_choice`、`top_p`、`stop`、`n`、`response_format` 等字段返回 400；OpenAI SDK 的常用参数需要按业务方反馈补齐（与 `/v1/responses` 一起评估） | T17b 已评估（ADR-0050 第 8 条）：对话补全与 Responses 都维持子集，不支持的字段返回明确的 400；按业务方反馈再扩展 `ModelInvoker.ChatRequest` 与三个供应商适配器（T26 的接入文档写明子集） |
| D27 | T09b | Embedding 与 rerank 只接入了 OpenAI 兼容格式（rerank 为 DashScope 的 `compatible-api/v1/reranks`）的供应商，`ProviderVectorClient` 不区分供应商类型；其他格式（DashScope 原生 rerank、Cohere 等）需要按供应商类型扩展 | T15a（以约束处理：Embedding 与 rerank 模型只能挂在 OpenAI 兼容账号下，ADR-0045；其他格式按需再接） |
| D28 | T09b | 用量记录新增 `kind`：`embedding_tokens`、`rerank_tokens`（用途 `embedding`、`rerank`，只有输入 token），control-plane 的消费者与价格表要按 `kind` 计价 | T16（已落地：Embedding、rerank 按模型的价格表输入单价计价，`UsageLedgerIT`） |
| D29 | T10a | harness 的 `WorkspaceContextMiddleware` 把受保护路径 `AGENTS.md`（智能体层的系统提示词）注入系统提示词的 `<agents_context>`，与装配时的 `sysPrompt` 重复，每次请求多一份提示词 | T10b（已落地：装配时 `disableWorkspaceContext()`，智能体层只经 `sysPrompt` 进入，当前时间以 `<platform_context>` 消息注入，ADR-0035） |
| D30 | T10a | 工作区（`rt.workspace_entries` 与对象存储 `rt/{tenant}/{session}/workspace/`）要在删除会话时按命名空间前缀与对象前缀清除；目前没有会话数据的清除作业 | T17a（已落地：`SessionPurger` 按 `namespace[4]` 删除工作区条目，并按条目记下的对象键与会话前缀删除大内容，ADR-0049） |
| D31 | T10a | `RemoteFilesystem` 的 `ls`、`glob`、`grep` 每次逐页读出整个会话命名空间（大文件也从对象存储读出），会话文件多时延迟与对象存储读取量上升；可在 `WorkspaceStorePort` 增加按前缀只读元数据的查询，或启用 `WorkspaceIndex` | R1 未测量（P7 的智能体没有文件工具）；改为 T29 的压测项 |
| D32 | T10b | 租户提示词在装配时读取并随智能体实例缓存（键不含租户设置），租户设置变更后实例淘汰前不生效；MCP 端点允许内网地址，登记时应校验端点（拒绝链路本地与元数据地址）| T15b（已落地：收到 `tenant_settings` 通知时经 `AgentEngine.evictAgentVersions` 淘汰该租户的实例，正在执行的本轮结束后关闭；登记 MCP 服务时按 `EndpointPolicy` 校验端点并探测工具清单，ADR-0047） |
| D34 | T11 | sandbox-router 以 `--authz-mode=allow-all` 运行：能访问 router 的调用方可以操作沙箱命名空间中的任意沙箱，目前只靠 NetworkPolicy 把 router 的入网限制在 runtime 的命名空间；生产部署时评估 `scoped-token` 或 `tokenreview` | T28 |
| D35 | T11 | 快照对象（`rt/snapshots/{tenant}/{project}/{session}/`）没有清除：从快照恢复后旧快照仍留在对象存储，删除会话时也要按前缀清除；`SnapshotStorePort` 目前没有删除方法 | T12a 已落地回收部分；T17a 已落地删除部分：`SessionPurger` 调 `deleteSessionSandbox`（快照与产物前缀），`SessionPurgeIT` |
| D36 | T11 | 2.0.4 的扩展把快照归档整个读进内存（`byte[]`），100 MB 工作区的快照或恢复在 runtime 侧占用约同等大小的堆；runtime 的堆与并发回收数要按工作区上限估算 | R1 已评估（回收器逐个处理，同一时刻一个快照在内存，6 GiB 堆下不构成约束，P7）；并发回收与更大工作区的配置给 T28 |
| D37 | T12a | 无人管理的沙箱：获取沙箱的事务在认领之后失败时会删除新沙箱，但 runtime 进程在认领之后、提交之前崩溃时，带会话标签 `meridian.refinex.cn/session-id` 的 `SandboxClaim` 没有记进任何会话，一直占用资源；需要一个按标签比对 `rt.sessions.sandbox_state` 的清理作业（也覆盖已删除会话的残留） | T17a 已落地已删除会话的部分：`SandboxDriver.deleteSessionSandboxes` 按会话标签删除（`SessionPurgeKindIT`），已删除的会话不再获取沙箱；存活会话的无主 claim 需要按集群全量比对，留给 T28 |
| D33 | T10b | `KnowledgePrefetchMiddleware` 前置检索注入的切片同样是外部内容，要用 `SourceTags`（`knowledge_search` 来源）包标签，并带 `ContextCompositionMiddleware` 的注入标记 | T19（已落地：`KnowledgePrefetchMiddleware` 用 `KnowledgeSearchTool.render` 包标签，标记 `meridian_knowledge_prefetch` 计入注入内容，`KnowledgePrefetchScenarioTest`） |
| D38 | T12b | 出网代理（Envoy 的 Lua 过滤器）回调 runtime 的 `/internal/v1/egress:authorize` 用开发期静态服务凭据（Secret `meridian-egress-proxy-credential`，本机取 `MERIDIAN_DEV_SERVICE_CREDENTIAL`，`dev`、`test` 之外不被接受）；Envoy 不能签发服务 JWT，T14b 要为代理另定凭据方式（例如 control-plane 为 `egress-proxy` 签发的长期凭据，或由边车刷新 JWT 写入文件、Lua 每次读取），并决定是否按调用方服务限定内部接口 | T14b（已落地：代理是服务客户端 `egress-proxy`，Lua 用客户端密钥换取服务 JWT 并按 worker 缓存；内部接口按调用方限定，ADR-0044） |
| D39 | T12b | 沙箱的网络策略放行集群 DNS（解析代理的服务名），CoreDNS 会把任意外部域名的查询转发出去，沙箱可以用 DNS 查询外带数据（DNS 隧道）；可选做法：CoreDNS 对沙箱网段只解析 `cluster.local`、沙箱改用固定 ClusterIP 的代理地址不放行 DNS，或 DNS 出口加策略（ADR-0039） | T37 |
| D40 | T12b | 出网代理的可观测与运维：管理接口只绑定 127.0.0.1，`/stats/prometheus` 没有被采集；访问日志（JSON，含源 IP、目标、状态码）只在容器标准输出；下线时不排空（SIGTERM 即退出，进行中的隧道被切断）；生产的沙箱节点池污点（`meridian.io/sandbox=true:NoSchedule`）在沙箱模板与代理上都还没有容忍配置 | T27（指标与日志）、T28（排空与节点池） |
| D41 | T13 | 子线程的事件经 `EventSinkPort` 逐条在各自的事务中写入（每条锁一次轮次行与会话行），事件量大、并行的子智能体会放大数据库写入；需要在容量测试中计入，必要时按批写入 | R1 未测量（P7 没有子智能体负载）；改为 T29 的压测项 |
| D42 | T13 | 子线程的 `session.error`（信封 `thread_id` 为子线程，`retry_status=terminal`）与主线程的同名事件类型相同：Webhook 订阅 `session.error` 时是否推送子线程的错误、是否按线程区分，需要在实现 Webhook 时决定 | T17a（已决：只推送主线程与会话级的事件，子线程的 `session.error` 不推送，ADR-0049） |
| D43 | T13 | 取消 AgentScope 的执行（失去租约、强杀模拟、子智能体超时）后，AgentScope 内部的 `CancellationException` 与被取消的供应商 HTTP 流经 Reactor 的 `onErrorDropped` 以 ERROR 记录（T06 起的恢复与 fencing 测试中同样出现），按 ERROR 告警会误报；可对这类取消降级日志 | T27 |
| D44 | R1 | 数据库连接池默认 10 是单节点的首要瓶颈（P7：3000 个 SSE 连接时等待 482，连接池 30 后 p99 127 毫秒）；会话沙箱的获取与回收在事务内做远程操作，期间占住一个连接（审查 C-02）。生产的 Hikari 连接池（至少 30）与 PostgreSQL 的 `max_connections` 要按 Pod 数规划 | T28 |
| D45 | R1 | 轮次认领是单线程串行的（`TurnWorkerLifecycle.drain`），单节点轮次启动速率约 70 至 74 轮/秒，并发轮次上限约为 70 × 平均每轮秒数（P7）；需要更高时并行认领或一次认领多个 | T28（容量规划），T29 复测 |
| D46 | R1 | SSE 每个连接每 5 秒一次对账查询，N 个连接即每秒 N/5 次，5000 个连接时连接池 30 也排队（P7）；可以在 `SessionStreamHub` 按会话共享一次对账 | T28（容量规划），T29 复测 |
| D47 | R1 | 一轮内有批次 checkpoint 时，结束时的保存走 AgentScope 的 CAS 冲突覆盖，每轮多一次写入并打一条 `agent_state CAS conflict — OVERWRITE applied` 的 WARN（审查 A-F3，2.0.4 没有同步版本号的入口）；按 WARN 告警会误报 | T27（日志降级或过滤），T30 复查 2.1 |
| D48 | R1 | 会话沙箱的远程操作在 `rt.sessions` 行锁内（ADR-0037），同会话发送事件的请求等到操作结束（最长 `operation-timeout` 10 分钟）；续租已不受影响（ADR-0041）。可评估改为按会话的咨询锁串行沙箱操作 | R2 结论：维持行锁（ADR-0037），改咨询锁要同时改获取、回收、清理、归档并重做 P9 的竞争测试；给 T28 的容量规划评估 |
| D49 | R1 | runtime 健壮性小项（审查）：C-07 工作区写入没有租约条件；C-08 steer 消费提交与批次 checkpoint 之间强杀会丢 steer；C-09 状态写入的围栏不含轮次 ID（先用故障注入确认）；C-12 可重试错误置回无退避、数据库瞬断计入恢复次数、终态后续租误计 `meridian.lease.lost`、下线时先改库后取消、`stop` 与 `drain` 竞态；C-13 回收器单行失败饿死后续；C-12 的 outbox 毒丸与持锁发布给 T16 | outbox 部分已由 T16 落地；R2 结论：C-07 与 R2 审查 C-01 的残留（租约丢失后仍在跑的工具写入）给 T37，C-08、C-09 用故障注入确认给 T29，C-12 的退避与指标给 T27，C-13 给 T28 |
| D50 | R1 | 沙箱与出网后续（审查）：C-10 回收先快照再删除再提交的失败代价、`sandbox_last_used_at` 取轮次开始时间、`findIdle` 不过滤已删除会话；C-11 `DatabaseSandboxStates.save`/`clear` 未接入出网撤销、删除与归档不淘汰出网缓存（最多 30 秒）；S-F4 产物在列出之后被后台进程继续写大、`readOutput` 整文件进堆（需要给 `SandboxDriver` 加带上限的读取） | T17a 已落地 C-11 与 C-10 的 `findIdle`；R2 结论：C-10 其余给 T28；S-F4 给 T37 |
| D51 | R1 | 安全加固（审查）：S-F5 自定义工具的 `input_schema` 不校验（灾难性回溯、远程 `$ref`）；S-F7 SSE 没有每 Key 连接上限、Key 吊销后连接保持；S-F8 `none` 档工作区没有配额、单文件上限按字符数；S-F13 来源标签的转义只覆盖 ASCII 空白；S-F14 批准时改写的入参是否重走规则；S-F15 审计与账本保存完整命令与入参、没有脱敏；S-F3 代理在 Envoy 层拒绝回环与未指定地址的上游 | T37 |
| D52 | R1 | 供应商接入前的校验（审查）：S-F11 `user.message` 的 `image.url` 不限协议，Anthropic、Ollama 的转换器会读本机文件或下载；S-F10 `SecretResolver` 不校验密钥属于调用方租户，供应商与搜索的 `base_url` 没有 SSRF 校验 | T15a（已落地：`image.url` 只接受 http(s)；凭据解析校验租户；`common.net.EndpointPolicy` 在写入供应商账号、模型、联网搜索后端时与 runtime 读取目录时校验端点；Gemini 适配器拒绝 URL 媒体，ADR-0045、ADR-0046） |
| D53 | R1 | AgentScope 集成后续（审查）：A-F5 装配自检失败不 `close` 智能体、MCP 资源在后续工具构造失败时不关；A-F6 `AgentEventDisposition` 与 `TurnEventMapper` 两张表各自独立；A-F7 `plan_write` 不经账本；A-F8 MCP 服务重启后的重连语义；A-F9 幂等键依赖供应商的 `tool_use_id`；A-F10 `HarnessAgent.close()` 在 commonPool 上等待 | T15b（A-F5）、T19（A-F8）已落地；R2 结论：A-F6、A-F7 不同意（两张表各有穷举测试；`plan_write` 没有外部副作用），A-F9 给 T30 复查 2.1，A-F10 给 T27 |
| D54 | R1 | 工程质量（审查）：Q-M1 token 估算在 runtime 与 engine 重复；Q-M2 重试退避重复且 `VectorModelService` 缺上限；Q-M3 `app` 依赖具体 `infra`、跨上下文依赖对方的 `infra`（修订规范或加 ArchUnit 规则）；Q-L1、L2、L3 摘要、Map 拷贝、输出上限常量重复；Q-L5 配置默认值双写；Q-L6 `application-test.yml` 在 `src/main`；Q-L8 三处无超时的 `.block()`；Q-L9 `Optional` 作参数；Q-L11 两处丢 cause；Q-L12 靠 `sleep` 的静默断言；Q-L13 `md_to_docx.py` 与执行服务符号链接的测试缺口；Q-L14 test-support 的 enforcer 多放行、`ScriptedOpenAiServer` 的 `Instant.now()` | T17b 已收口重复实现；R2 结论：Q-M3 维持现有约定（`app` 可依赖本上下文的 `infra`，跨上下文只经对方的 `app`，不新增规则）；Q-L6 给 T28，其余小项给 T27 |
| D55 | T14a | MVC 服务的公开 API 还没有 API Key 过滤器与按 Key 限流：`meridian-security` 只有 WebFlux 版（`ApiKeyWebFilter`）；control-plane 的 `/v1/agents`（T15b）、knowledge 的 `/v1/knowledge-bases*`（T18）上线前要加 MVC 版，复用 `ApiKeyAuthenticator`、`ApiKeyRateLimiter`，并订阅 `key.revoked` 淘汰缓存 | T15b（control-plane 已落地：`meridian-security` 的 `PublicApiAdmission` 由 WebFlux `ApiKeyWebFilter` 与 MVC `ApiKeyFilter` 共用，control-plane 用进程内的 `ApiKeyResolver`，吊销立即生效，`ApiKeyFilterTest`），T18（knowledge 已落地：`ApiKeyFilter` 加 `ControlPlaneApiKeyResolver`，`web.ApiKeyRevocationListener` 订阅 `key.revoked`，`KnowledgeApiIT`） |
| D56 | T14a | `config.invalidate`、`key.revoked` 走 Pub/Sub，不保证送达：吊销有 60 秒缓存过期兜底，但租户并发配额的变更丢失后，runtime 的 `max_running` 要等下一次变更才更新；需要一个定期对账（按 control-plane 的配额刷新已有计数行）或改用 Stream | T27 |
| D57 | T14a | 平台管理员来自配置 `meridian.control-plane.platform-admins`（主体标识列表）；Q2 已决（ADR-0043）：T14b 再支持按组或角色声明配置 | T14b（已落地：`meridian.control-plane.platform-admin-groups` 按令牌的组声明判定，ADR-0044） |
| D58 | T14b | 服务 JWT 的生产部署（部署文档待办）：签名密钥从密钥管理系统加载（实现 `servicetoken.app.SigningKeySource`；开发实现 `DirectorySigningKeys` 只适合单副本或共享卷）、多副本 control-plane 共用同一组密钥、定期轮换的触发方式（`rotate()` 目前只有测试调用）；各服务的客户端密钥由 Secret 管理系统下发与更换；接入商业 IdP（Entra ID 的 `oid` 与组 ID、Okta 的组声明）的配置说明 | T28（Helm 与部署文档）、T26（接入说明） |
| D59 | T15a | 凭据库的生产部署：`key-encryption=vault` 的地址、令牌与命名空间由 Secret 管理系统下发，Transit 密钥的轮换周期与 `rewrap`；从 `local` 切到 `vault`（或换主密钥）后旧的数据密钥解不开，需要一个重新包装数据密钥的迁移作业；`provider-endpoints.private-networks` 按私有化推理服务所在网段收窄（ADR-0045） | T28（部署文档与 Helm） |
| D60 | T15a | Anthropic、Gemini 适配器只经 MockWebServer 验证（本机没有两家的真实密钥），没有 `-Plive`；扩展思考（Anthropic 的 thinking、Gemini 的 thinking budget）与提示词缓存的计量没有真实数据核对（ADR-0046） | T23（连通性测试接入真实账号时补跑），T30 复查 2.1 |
| D61 | T15a | 联网搜索后端的端点只在 control-plane 写入租户设置时校验，runtime 侧的租户设置仍读开发配置、没有再校验；`tenant_settings.web_search.secret_id` 没有校验凭据存在且是租户级 | T15b（已落地：runtime 读取租户设置时按地址策略再校验联网搜索端点，不允许时按未配置处理；control-plane 写入时校验 `secret_id` 存在且是租户级，ADR-0047） |
| D62 | T15a | 端点校验与实际连接之间的 DNS 重绑定：control-plane 写入时、runtime 刷新目录时各解析一次，供应商客户端连接时再由 JDK、OkHttp 解析，两次之间换了地址校验不到；以网络策略为最终边界（ADR-0045） | T37 |
| D63 | T15b | `dev-config/` 的剩余用途只有测试与本地开发：`test` profile 的 `agent-config.source`、`catalog-source` 为 `file`（测试夹具读 `dev-config/agents`、`dev-config/providers`）；`dev` profile 默认 `file`（`run-runtime.sh`、Compose `app` 的 runtime 挂载 `dev-config/`）；预算限额 `dev-config/budgets`。其他 profile 选 `file` 时启动失败，生产只经 control-plane | T16（已落地：预算限额改由 control-plane 写进 Redis，`budget-source=file` 只允许 test），T28（生产 chart 不挂载 `dev-config/`） |
| D64 | T15b | `TODO(T18)`：发布时知识库的存在性由 control-plane 的 `ConfiguredKnowledgeCatalog` 按配置 `meridian.control-plane.knowledge.dev-knowledge-base-ids` 判定，没有经 knowledge 服务校验存在且属于该项目 | T18（已落地：`KnowledgeServiceCatalog` 调 knowledge 的 `GET /internal/v1/knowledge-bases/{id}` 并比较租户与项目，出站服务 JWT 由 control-plane 进程内签发，ADR-0051，`AgentVersionIT`） |
| D65 | T15b | 引用检查只统计未删除智能体的已发布版本：智能体软删除后，绑定其版本的会话仍可运行，但它引用的模型、凭据、MCP 服务、技能可以被删除，这些会话的下一轮解析失败（ADR-0047 第 4 条） | R2 结论：维持 ADR-0047 第 4 条（会话照常运行，被引用资源删除后下一轮明确报错；控制台删除对话框写明后果） |
| D66 | T16 | runtime 测试偶发失败：BlockHound 在 Netty 线程上抓到 `Ids.newUlid()` 的阻塞（`ulid-creator` 单调工厂的 `ReentrantLock` 在并发生成时争用，`RequestIdWebFilter` 在事件循环上调用），表现为某个请求 500（T16 开工基线中 `SubagentSessionIT` 的 SSE 连接，重跑通过；T19 的基线与收尾中 `SubagentSessionIT`、`EventStreamIT` 各一次，主机负载高时更常见）。可选：`RuntimeBlockHoundIntegration` 放行 `UlidFactory.create`（临界区只有几条指令），或请求 ID 改用非单调 ULID | R2（已落地：`RuntimeBlockHoundIntegration` 放行 `UlidFactory.create`） |
| D67 | T16 | `rt.sessions.budget_limit`（会话级预算的金额）没有使用：会话级预算改在 control-plane 的 `budgets`（token，ADR-0048）。列保留在 V0001 中，是否删除或改作创建会话时的会话级硬阈值待定 | R2（已落地：rt `V0013` 删除该列，ADR-0054） |
| D68 | T16 | 用量报表只有聚合，没有明细列表与 CSV 导出；T25b 的“明细表、导出 CSV”需要按游标列出 `usage_records` 的接口 | T19b（已落地：租户与项目的 `usage-records` 与 `:export`，至多 100000 行，ADR-0053，`UsageReportIT`） |
| D69 | T16 | `cp.stream_dead_letters` 只有写入：没有查看与重放的接口或脚本，坏消息只能人工查表后重新发布；outbox 中 `attempts` 达到上限的消息同样只能人工处理 | R2 结论：瞬时故障不再让 outbox 停投（审查 K-03 已修）；死信与停投消息的查看、重放与告警给 T27 |
| D70 | T16 | 知识检索的次数没有计量：`search_calls` 只记联网搜索；知识库存储量的用量种类由 T18 加入 `UsageKind`、`UsageMessages` 与 `usage_records.kind` 的检查约束 | T18（存储量已落地：`knowledge_storage_gb_days`，cp `V0006`，`StorageMeteringIT`、`UsageLedgerIT`），T19（检索次数已落地：`knowledge_search_calls`，cp `V0007`，每次检索一条、带入口 `rest`/`mcp`/`agent`，`KnowledgeSearchIT`、`UsageLedgerIT`） |
| D71 | T17a | 事件归档把会话的全部现存事件读进内存再压缩上传（`EventArchiver.readAll`、`EventArchives.write`），保留清理重写文件同样整份读入；单个会话事件很多（数十万条）时内存占用大，应改为流式读写与分段上传 | T28（容量与运维） |
| D72 | T17a | Webhook 的签名密钥不能轮换（只能删除后重建，Webhook ID 随之改变）；投递记录的列表只按 Webhook、会话、状态过滤，没有时间范围 | T19b（已落地：`POST …/webhooks/{id}:rotate-secret`、投递记录按 `created_after`/`created_before` 筛选，ADR-0053，`WebhookAdminIT`、`ConsoleQueriesIT`；轮换没有新旧密钥并存的过渡期，见 D84） |
| D73 | T17a | 删除通知的投递与会话清理的先后：清理等待该会话的 Webhook 投递至多 12 小时，之后未完成的投递随会话删除（外键级联），投递记录不再可查；接收方长时间不可用时业务系统收不到删除通知 | R2 结论：维持（ADR-0049）；需要独立保留投递记录时由 T28 评估 |
| D74 | T17b | 上传的文件（`rt.files` 与 `rt/{tenant}/{project}/files/`）没有删除接口与保留期，随项目一直累积；放进会话工作区的副本随会话清除 | R2 结论：需要新接口，登记 D87 |
| D75 | T17b | 按文件 ID 引用的图片作为文件放进工作区，不作为图片输入交给模型（视觉）：内核要读出内容转成 Base64 图片块，涉及大小上限与预扣估算 | M2（按业务方需要） |
| D76 | T17b | 同一事件的 `created_at` 在 SSE 帧（`Instant`，保留 6 位小数）与列表接口（`OffsetDateTime`，去掉末尾的 0）中的文本表示不同（同一时刻；开工基线 `EventArchiveIT` 偶发失败的原因） | T26（SDK 与接入文档时统一时间的序列化） |
| D77 | T17b | 引用平台文件的 `user.message` 按 queue 投递，保存的事件中 `delivery` 仍是请求的值（与跟在中断后面的消息相同）；调用方要从事件判断实际投递方式时需要额外字段 | T26 |
| D78 | T18 | knowledge 调 runtime 的内部模型接口只在请求体带租户与项目，知识库 ID 没有进入用量消息与账本（架构设计「跨服务数据流」写的是"请求头带上租户、项目、知识库"）；按知识库看 Embedding、rerank 的用量要改内部接口、runtime 的用量消息、样本与 control-plane 的解析 | R2 结论：要改内部接口与用量消息，登记 D88 |
| D79 | T18 | doc-parser 不校验服务 JWT，只靠集群内网与网络策略限定只有 knowledge 能访问（ADR-0051）；Helm 中的 NetworkPolicy、内存限额（至少 3 GB）与 CPU 还没有落地 | T28 |
| D80 | T18 | PDF 的路由靠启发式：平均每页少于 50 个可见字符视为扫描件，横竖各至少 3 条线视为表格；没有框线的表格按正文切分，Word 文档没有页码。检索效果受切分影响，P8 定稿时按真实文档复核 | R2 结论：给 T29（端到端验收用真实 PDF 复核路由与页码） |
| D81 | T18 | 知识库存储量取每日执行时的大小（不是当天的平均值）；知识库本身没有删除与修改接口，`storage_reports` 只随知识库删除 | T19b 已落地修改与删除；R2 结论：存储量取执行时的大小维持，T26 的计量说明写明 |
| D83 | T19b | 删除知识库不检查已发布智能体版本的引用（引用在 control-plane 的 `agent_version_refs`，检查要 knowledge 同步调用 control-plane）：引用它的智能体之后检索得到"知识库不存在"的工具错误，要发布新版本去掉引用 | R2 结论：登记 D90 |
| D84 | T19b | Webhook 签名密钥轮换立即替换，没有新旧密钥并存的过渡期：接收方要先接受新密钥，否则轮换后到更新前的投递验签失败、按退避重试 | T26（接入文档写明轮换步骤）；R2 结论：双密钥签名按业务方反馈再做 |
| D85 | T19b | 按智能体的统计与会话筛选依赖 `rt.sessions.agent_id`（V0012 新增），之前创建的会话为空、不计入；`TurnStatsRollup` 第一次运行时从 8 天前开始汇总 | T28（升级说明） |
| D82 | T19 | MCP SDK 0.17.2 的服务端会话保存在 knowledge 进程内，只在 DELETE 或推送失败时移除，没有空闲过期：多副本要按 `Mcp-Session-Id` 粘滞路由，不发 DELETE 的客户端会让会话累积（P8） | R2 结论：改用 SDK 0.17.2 的无状态传输可消除，登记 D89（T28） |
| D86 | R2 | API Key 没有"最后使用时间"：控制台「设置 / API Key」要展示（T19b 的对照表写成了已有），control-plane 不记录。做法：`cp.api_keys.last_used_at`，`api-keys:verify` 命中时按分钟粒度更新，`ApiKey` 加字段 | 补充任务（需要你决定），或并入 T25b |
| D87 | R2 | 上传文件（`rt.files` 与 `rt/{tenant}/{project}/files/`）没有删除接口与保留期（原 D74） | 补充任务或 T26 |
| D88 | R2 | 知识库 ID 没有进入 Embedding、rerank 的用量消息与账本，按知识库看用量要改内部接口、runtime 的用量消息与 control-plane 的解析（原 D78） | 按业务方需要，T27 之后 |
| D89 | R2 | knowledge 的 MCP 服务端会话在进程内、没有过期（原 D82）：工具无状态，改用 SDK 0.17.2 的 `HttpServletStatelessServerTransport` 即可消除会话与粘滞路由，需重跑 P8 的 Inspector 验证 | T28 |
| D90 | R2 | 删除知识库不检查已发布智能体版本的引用（原 D83）：检查要 knowledge 同步调 control-plane 的引用查询 | T25a（控制台确认对话框写明后果），引用查询按需要再做 |
| D91 | R2 | API 契约与错误处理的收尾（审查 A-03、A-05、A-06、A-07 的状态码、A-08、A-12、A-13、A-16、A-18、A-19）：契约测试检查未声明的状态码；公开规范声明 `Meridian-Project` 头与控制台身份的错误码；401 带 `WWW-Authenticate`；knowledge 检索把下游的 429、502 分别映射；路径 ID 按前缀校验；两种 Web 栈的框架错误说明一致；405、415 带 `Allow`、`Accept`；模型 429 带 `Retry-After`；`RateLimit-*` 与 503 的声明 | T26 |
| D92 | R2 | 安全加固（审查 I-09、I-10、K-09 的决定人、K-13、K-14、K-15、K-19、A-15）：runtime 缺 `PlatformContext` 时配置归属校验失败关闭；控制台只读角色的知识库标签自报；`permission_rule.remembered` 带决定人；URL 查询串进审计与日志；凭据解析失败的审计与指标、内部 Webhook 订阅接口的审计；项目与租户删除后凭据、Webhook 的清理与解析拒绝；生成 DTO 的 `toString` 带敏感字段；上传在作用域检查之前落盘 | T37 |
| D93 | R2 | 部署（审查 K-08、K-11、K-12、K-17、A-14、A-24）：每个服务一个数据库角色、只授权自己的 schema；本地主密钥文件按 0600 原子创建；Vault Transit 带租户上下文与令牌续期（与 D59 一起）；令牌端点只对集群内开放并限速、失败计数；网关统一容器层错误为 problem+json；knowledge 的 multipart 请求上限略大于文件上限 | T28 |
| D94 | R2 | 作业与计量的健壮性（审查 K-10、K-16、K-18、C-02 的归档与保留、C-03、C-04 的告警、C-05、C-06、C-07、C-09、C-10、C-11、C-14、C-15）：保留清理的审计按会话同事务；单个 Webhook 解密失败不拖垮整个项目；消费者校验消息时间的范围；归档与保留作业的队头推迟；失败率汇总改为按"未计入"标记（不依赖水位延迟）；用量记录写失败的告警；对账读快照原子化；`budget_usage` 按键排序累加；Webhook 的认领期与批量；归档提交结果不确定时的文件删除；扇出前预取订阅；取消路径的 `session.usage`、上传与知识库删除竞争、邀请绑定竞争用并发测试确认 | T27（告警、审计、消费者、失败率）、T28（作业与配置）、T29（并发测试） |
| D95 | T21 | 控制台的部署（ADR-0055）：静态资源服务对未知路径回退到 `index.html`（含 `/auth/callback`、`/auth/silent-callback`）；按环境提供 `/config.json`（身份提供方的地址与客户端，构建产物里的是开发默认值）；身份提供方的控制台客户端登记两个回调地址与退出后的回跳地址；入口按 ADR-0055 的路径表转发；控制台与身份提供方部署为同站，否则刷新页面后的 iframe 静默登录退化为整页跳转 | T28 |
| D96 | T21 | CI 的 `verify` 作业没有安装 uv（`verify.sh` 的 sandbox-runtime、doc-parser 两步需要）；T21 补了 Node 24 与 pnpm，新增的 `console` 作业的每一步都在本机用等效命令跑过，但 CI 本身还没有在 GitHub 上运行过 | T28（发布流水线）或下一次改 CI 的任务 |
| D97 | T21 | ⌘K 命令面板只有页面：智能体（跳到智能体详情）随 T22 加入（已完成：`CommandObjectGroup`，第一次打开时加载），最近的会话与输入会话 ID 直接打开随 T24 加入（T20c 原型有这两组） | T24 |
| D98 | T22 | 智能体详情的"会话"标签页列出使用中的会话，行还不能打开会话详情（会话详情页在 T24）；版本表与修订历史的"发布或创建""修改人"只有主体标识（显示"你"或截断的 `sub`）：管理 API 没有按主体查成员显示名的接口 | T24（会话行跳转）；显示名按需要再补接口 |
| D99 | T22 | 编辑页模板在顶部时，分组导航按"标题进入容器上部 40%"高亮第二组（表单较短时第二组标题也在上部 40% 内）：模板行为，T20c 定下的阈值 | R3 |
| D100 | T23 | 模型、供应商账号、MCP 服务的管理 API 没有引用方字段（技能 T23 已加、凭据原有）：删除被引用的对象时要先输入名称、被拒（409）后控制台才列出引用方；按 `Skill.references` 的做法给三者加 `references` 即可在删除前直接说明 | R3（决定是否补接口） |
| D101 | T23 | 端到端用例与截图用例在验收租户里留下的数据只增不减（价格表没有删除接口，模型、技能等用例不清理）：列表越来越长，按名称前缀选择的用例可能互相干扰（T23 曾因此改截图用例的技能前缀） | 下一次改端到端基座的任务（按运行建临时项目或在全局准备中清理） |

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

### 2026-11-08 T08 已完成（提交 f75bc4f）
- 交付：runtime `approval`（`PermissionSynthesizer` 五层合成与 unattended、子线程改写，`PlatformSafetyRules`，`PolicyStorePort` 与 `rt.permission_rules` 开发实现，`PendingActionResolver` 结论、"记住此决定"与恢复轮，`ResumptionPlanner`，`PendingActionTimeouts` 超时扫描，`ApprovalAudit`）；`delivery.app.EventIntake` 按线程状态处理 queue、steer、中断与回应；`dispatch.app.NodeChannel`/`NodeChannelListener`（`node:{lease_owner}` 立即续租）；内核 `SteerMiddleware`、`InterruptCheckpointMiddleware`、`AgentEngine.retryToolCall`/`reservedToolNames`、`TurnContext.interruptRequested`；迁移 `V0004`；创建会话校验保留工具名；testkit `PathRuleTool`；集成测试 `DeliveryMatrixIT`、`ApprovalFlowIT`、`PermissionRewriteIT`、`RecoveryConfirmationIT`、`SteerRecoveryIT`、`CustomToolDeclarationIT`；P6（审批部分）、P10 补全、P15 报告
- 验收：11 项通过，0 项阻塞
- 决策：ADR-0031（合成的并集与排序、平台安全规则、平凡权限上下文与自定义工具放行、子线程 allow 子集、中断与"带中断请求的恢复轮"、结论与恢复轮、`replay_input` 重放原轮输入、无人值守自动拒绝以 `tools_denied` 结束、超时扫描、审计格式）；架构设计升至 v1.7
- 遗留：登记事项 D21 内容级"记住此决定"（T10a、T12a）、D22 策略迁到 control-plane 与默认智能体需显式放行工具（T15b）；T06、T07 中按占位行为写的 4 处断言改为 T08 语义（`TurnRecoveryIT`、`WorkerLeaseFencingIT`、`TurnWorkerIT`、`SessionApiIT`）

### 2026-11-08 T09a 已完成（提交 5e8b21a）
- 交付：engine-spi 的 `ModelGateway` 补充（`describe`、`precheck`、`ModelCallRecord`）、`ContextComposition`、`ProviderUnavailableException`/`ProviderRejectedException`/`CompactionFailedException`；engine-agentscope 的 `model` 包（`RoutedModel` 与 `RoutedModelFactory`、`ProviderClients`、`OpenAiCompatibleClientFactory`、`ProviderHttpTransport`、`TokenEstimator`）、`CompactionGuardMiddleware` 内外两层、`ContextCompositionMiddleware`、平台摘要提示词；runtime 的 `modelrouting`（`RoutingModelGateway`、Redis 令牌桶与四级预算 Lua、outbox `usage` 用量、三个指标、`dev-config/providers` 与 `dev-config/budgets` 文件实现）、`EnvironmentSecretResolver`、`ModelFactoryAutoConfiguration`；OpenAPI `span.model_request_start.context`；testkit 的 `InMemoryModelGateway`、`StaticProviderClientFactory`；集成测试 `RoutedModelProviderIT`（mockwebserver3）、`ModelRoutingWorkerIT`、`ModelBudgetSettlementIT`、`RateLimitWaitIT`、Lua 脚本测试；真实模型测试 `ModelLiveIT`；P13 报告
- 验收：9 项通过，0 项阻塞
- 决策：ADR-0032（重试与结算细则、平台 HTTP 传输拿 `Retry-After`、失败摘要先恢复上下文再抛异常、`CUSTOM` 的平台事件、压缩只按 token 阈值、预算以 token 计、用量消息格式、供应商扩展点）；架构设计升至 v1.8；沿用 ADR-0023 的 `maxRetries(1)`（任务描述写的 0 在 2.0.4 中非法）
- 遗留：登记事项 D23 开发期模型目录不按项目授权（T15a）、D24 预算的周期、金额与对账（T16）、D25 Anthropic、Gemini 等供应商类型未接入（T15a）；修复 T06 `TurnExecution` 的指标记录竞态（`TurnMetricsIT` 偶发失败）

### 2026-11-08 T09b 已完成（提交 003dbfc）
- 交付：engine-agentscope 的 `RoutedModelInvoker`（`ModelInvoker`，流式工具调用片段按序号归并）；runtime 的 `modelrouting.api`（`/v1/chat/completions` 流式与非流式、`/v1/embeddings`、`/internal/v1/embeddings`、`/internal/v1/rerank`）、`VectorModelService` 与 `ProviderVectorClient`（拆批、限流、预扣、计量、重试）、模型目录的 `kind`、模型级 `base_url`、`max_batch_size` 与按 profile 加载；engine-spi 的 `CallPurpose.EMBEDDING`/`RERANK`；security 的 `servicejwt` 包（开发期静态服务凭据，只在 dev、test）；`meridian-v1.yaml` 两个操作与新文件 `meridian-internal-v1.yaml`；test-support 的 `ScriptedOpenAiServer`、8 个内置脚本与 `scripts/dev/scripted-model.sh`（默认端口 18090）；`dev-config/providers/scripted.yaml` 与 DashScope 的 Embedding、rerank 模型；测试 `RoutedModelInvokerTest`、`ScriptedOpenAiServerTest`、`ScriptedProviderContractIT`、`ModelEndpointsIT`、`ScriptedModelSessionIT`、`ModelEndpointsLiveIT` 等
- 验收：6 项通过，0 项阻塞
- 决策：ADR-0033（一个操作两种响应与多类型字段的生成方式、流式先等第一个信号、请求严格与错误格式、非对话模型的计量与拆批、内部接口的租户与服务凭据、脚本服务的无状态选择与断流实现）；架构设计升至 v1.9
- 遗留：登记事项 D26 直通接口支持的 OpenAI 字段（T17b）、D27 Embedding 与 rerank 的供应商类型（T15a）、D28 用量记录的新 `kind`（T16）

### 2026-11-08 T10a 已完成（提交 b724148）
- 交付：engine-agentscope 的 `tools`（`PlatformTool` 基类：规则内容语法 `RuleSyntax`、`matchRule` 与带内容的 `generateSuggestions`、账本与幂等键、`non_idempotent` 结果引用；六个文件工具与先读后改、输出截断、`grep_files` 按拒绝规则过滤；`PlatformToolProvider`）、`fs`（`MeridianBaseStore`、`ReadOnlyFilesystem`、`ProtectedContent`、`MeridianSkillRepository`、`PlatformWorkspace`、`WorkspaceNamespaces`）、`assembly.ToolPolicy`（装配与启动自检、策略拒绝规则）、`PolicyMiddleware`；common 的 `PlatformContext.endUserId`；engine-spi 的 `SkillDefinition`、`AgentConfigPort.skill`、`ResolvedAgentSpec.grants`；runtime 的 `workspace`（`DatabaseWorkspaceStore`、迁移 `V0005`）、`PlatformToolsAutoConfiguration`、`meridian.runtime.worker.enabled`、账本的 `meridian.tool.calls` 与审计 `tool.executed`；开发配置的文件工具与示例技能；ArchUnit 规则 13；测试 `FileToolScenarioTest`、`SkillScenarioTest`、`PlatformToolLedgerTest`、`PolicyMiddlewareScenarioTest`、`ToolPolicyTest`、`RuleSyntaxTest`、`FileToolsWorkerIT`、`DatabaseWorkspaceStoreIT`、`ToolkitSelfCheckStartupIT`；P4（`none` 档）、P6（文件工具部分）报告
- 验收：6 项通过，0 项阻塞
- 决策：ADR-0034（`PlatformContext` 带终端用户；`wait_async_results` 在 2.0.4 中是 `ToolBase`、`onActing` 中间件的事件不发布，工具集外的工具由策略拒绝规则经权限引擎拒绝；技能仓库继承 `WorkspaceSkillRepository` 避免本机暂存；先读后改的读取状态随工具结果元数据持久化；工作区存储细则；本机工作区根；Worker 开关）；架构设计升至 v1.10；开工时修复 `AgentScopeEngineShutdownTest` 的下线竞态
- 遗留：登记事项 D21 内容级"记住此决定"的带出与保存（T12a）、D29 `AGENTS.md` 与 `sysPrompt` 重复（T10b）、D30 删除会话时清除工作区（T17a）、D31 工作区列举读出整个命名空间（R1）

### 2026-11-08 T10b 已完成（提交 7e490f5）
- 交付：engine-agentscope 的提示词分层（`PlatformPrompt`、`TenantPromptProvider`、`PlatformContextMessage`，装配时 `disableWorkspaceContext()`）、`SourceTags`、`WebFetchTool`、`WebSearchTool`、`KnowledgeSearchTool`、`tools.mcp`（`McpConnector`、`McpPlatformTool`、`McpCredentials`、`McpCredentialInjector`）、`PlatformToolProvider` 的按装配对象工厂与 MCP、`PlatformTool` 入参 schema 按键排序、`AgentScopeEngine` 注入时钟；engine-spi 的 `AgentConfigPort.tenantPrompt`、`ToolErrorCode`/`McpUnavailableException`、`McpServerSpec` 副作用缺省值；runtime 的 `tooling`（`AddressPolicy`、`GuardedWebFetcher`、`GuardedResolverGroup`、`WebContentConverter`、`TenantWebSearch` 与 SearXNG、博查适配器、`UnconfiguredKnowledgeSearch`）、`TenantSettings` 与 `kind: tenant_settings`、装配；test-support 记录请求体；BOM 加 flexmark、jsoup、WireMock；开发配置的租户设置与联网工具；测试 `PromptLayeringScenarioTest`、`ExternalContentToolScenarioTest`、`SourceTagsTest`、`McpToolsIT`（Boot 4.1 Servlet 的进程内 MCP 服务）、`AddressPolicyTest`、`GuardedWebFetcherTest`、`WebSearchAdaptersTest`、`ScriptedModelSessionIT` 前缀用例；P8 预验证报告
- 验收：7 项通过，0 项阻塞
- 决策：ADR-0035（提示词分层的落点与时间以消息注入；租户层经 `AgentConfigPort` 在装配时读取；来源标签格式与转义；`web_fetch` 用 Reactor Netty 加校验解析器、不复用连接、平台解析 IP 字面量；商业搜索选博查；`knowledge_search` 按实例创建；MCP 用异步客户端、凭据经单次调用的 Reactor Context 注入、工具包成 `PlatformTool`、连不上服务为可重试错误、MCP 端点允许内网但拒绝元数据地址）；架构设计升至 v1.11；登记事项 D29 已落地
- 遗留：登记事项 D32 租户设置变更与 MCP 端点登记校验（T15b）、D33 前置检索的切片包来源标签（T19）

### 2026-11-08 T11 已完成（提交 bcd2246）
- 交付：`sandbox-runtime/`（Debian trixie slim、uid 1000、uv 管理的 Python 3.13 与文档处理库、Node.js 24、tini；FastAPI 执行服务 `/execute`、`/upload`、`/download`、`/list`、`/exists`，超时、输出上限、进程组整体终止，接口对照表与预装清单在 README，pytest 接入 `verify.sh`）；`scripts/dev/install-agent-sandbox.sh`（agent-sandbox v1.0.10，校验清单摘要与镜像版本）、`scripts/dev/deploy-sandbox.sh`；chart `deploy/helm/meridian-sandbox`（三个规格的 `SandboxTemplate` 与 `SandboxWarmPool`、sandbox-router、默认拒绝出网的 NetworkPolicy、runtime 的 Role，`values-kind.yaml`）；engine-spi 的 `SnapshotStorePort`、`SandboxErrorCode`、`SandboxUnavailableException`；engine-agentscope 的 `sandbox` 包（`KubernetesSandboxDriver`、`SingleStartSandbox`、`PlatformSnapshotClient`、`SandboxDriverSettings`）；runtime 的 `sandbox.app.ObjectStoreSnapshotStore` 与 `SnapshotStorePort` Bean；persistence 的 `ObjectStore.exists`；根 POM 的 `kind` profile；测试 `SingleStartSandboxTest`、`PlatformSnapshotClientTest`、`ObjectStoreSnapshotStoreIT`、`KubernetesSandboxDriverKindIT`、`SandboxPoolMeasurementKindIT`；P5 报告
- 验收：8 项通过，0 项阻塞
- 决策：ADR-0036（sandbox-router 部署在沙箱命名空间；模板 `Unmanaged`、chart 自带默认拒绝出网的策略；`/workspace` 为指向 emptyDir 中 uid 1000 目录的符号链接，emptyDir 挂载点上恢复快照的 `tar` 会失败；进程数用 `ulimit -u`，gVisor 中按沙箱计数；新增 `SnapshotStorePort`；驱动的完整单次选项、`resume` 先查 claim；本机端口转发、生产直连 router；`kind` 测试的命名与 profile）；架构设计升至 v1.12；登记事项 D03 关闭
- 遗留：登记事项 D34 router 的鉴权（T28）、D35 快照对象的清除（T12a、T17a）、D36 快照在 runtime 侧的内存占用（R1）；kind 集群 `meridian` 保留给 T12a、T12b（`kind delete cluster --name meridian` 释放内存）

### 2026-11-08 T12a 已完成（提交 b3bdef7）
- 交付：engine-spi 的 `SandboxDriver.syncSkills`/`listOutputs`/`readOutput` 与 `ToolLedgerPort.recordSandboxCommand`；engine-agentscope 的 `tools.BashTool`（命令前缀逐段匹配、`checkPermissions` 补拒绝与询问的任一段命中和多规则放行、`timeout` 落实超时、30000 字符保留头尾、沙箱命令审计）与 `ShellCommands`，`fs.SandboxWorkspaceFilesystem`（路径翻译到 `/workspace`、`glob` 语义、要求本次调用绑定的沙箱），`sandbox.TurnSandboxes`、`SandboxAttachment`、`PlatformManagedSandboxClient`、`SkillProjection`，装配器为 sandbox 档重建工具用的 `RoutedSandboxFilesystem`，`AgentScopeEngine` 每轮注入外部 `SandboxContext`；runtime 的 `sandbox` 上下文（`SandboxService` 获取、回收、产物同步、计量、删除会话的沙箱部分，`DatabaseSandboxStates`、`IdleSandboxReaper`、`SandboxProperties`、仓储与领域类型）、`TurnExecution` 的准备与收尾、`config.SandboxConfiguration`、迁移 `V0006`、`DatabaseToolLedger` 的 `sandbox.command` 审计、"记住此决定"对 bash 记命令原文、指标 `meridian_sandbox_claim_seconds`；内置技能 `skills/builtin/docx`（`verify.sh` 检查其脚本）；测试 `BashToolScenarioTest`、`ShellCommandsTest`、`SandboxWorkspaceFilesystemTest`、`SkillProjectionTest`、`RememberedRulesTest`、`SandboxServiceIT`、`DatabaseToolLedgerIT` 新用例，kind 测试 `SandboxSessionKindIT` 与 `KubernetesSandboxDriverKindIT` 新用例；P4、P6 补全，P9 报告
- 验收：8 项通过，0 项阻塞
- 决策：ADR-0037（获取与回收都在会话行锁内、删除先于提交、每个会话至多一个快照；内核经 `SandboxStatePort` 读沙箱、拒绝自建沙箱的客户端；工具文件系统的路径翻译与沙箱绑定；bash 复合命令语义、超时与输出上限；沙箱命令审计走 `ToolLedgerPort`；技能摘要算法；产物镜像同步与 64 MB 上限；vCPU 秒分段计量与 `sandbox_vcpu_seconds`；认领指标只计预热池认领；bash 的"记住此决定"记命令原文）；架构设计升至 v1.13；登记事项 D35 回收部分落地
- 遗留：登记事项 D21 内核带出建议规则（建议 T19b）、D35 删除会话时调用（T17a）、D37 无人管理的沙箱清理（T17a 或 T28）；kind 集群 `meridian` 保留给 T12b

### 2026-11-08 T12b 已完成（提交 086b6f4）
- 交付：runtime 的 `egress` 上下文（`domain`：`EgressTarget`、`EgressAllowlist`、`EgressDecision`、`EgressGrant`、`EgressErrorCode`；`app`：`EgressAuthorizer`（指标 `meridian.egress.authorize`）、`EgressGrants`（Caffeine 按源 IP 缓存 30 秒，命中率经 `CaffeineCacheMetrics`）、`EgressRevocations`（本节点淘汰，提交后经 Pub/Sub `egress:revoke` 广播）；`infra.system.SandboxAddressRepository`；`api.EgressApiController`）、迁移 `V0007`、`SandboxService` 在 Pod IP 变化时撤销授权；内部接口 `POST /internal/v1/egress:authorize`；chart 的出网代理（Envoy v1.40.2：Lua 回调、CONNECT 与动态转发代理、超时与连接上限、健康检查监听）、沙箱只到代理与 DNS 及代理自身的网络策略、`cluster`/`host` 两种拓扑、README；`deploy/kind/egress-echo.yaml`、`deploy-sandbox.sh` 建回调凭据 Secret；`kind-gvisor.sh` 的 Pod 网段改为 `10.244.0.0/16`（集群已重建）；开发配置 `env_dev_sandbox`；测试 `EgressTargetTest`、`EgressAllowlistTest`、`EgressAuthorizerIT`、`SandboxEgressRevocationIT`、`EgressApiIT`、kind 测试 `EgressProxyKindIT`；P14 报告
- 验收：4 项通过，0 项阻塞
- 决策：ADR-0038（变更型，用户采纳：`ext_authz` 的 HTTP 服务把客户端方法带进检查请求，授权不了 CONNECT，改用 Envoy Lua 过滤器回调）；ADR-0039（按 IP 找会话与两行同 IP 拒绝、白名单语法、缓存的跨节点失效、接口的 403 problem+json、网络策略、两种拓扑、kind 网段、回调凭据）；架构设计升至 v1.14
- 遗留：登记事项 D38 代理的回调凭据（T14b）、D39 DNS 隧道（T37）、D40 代理的指标、排空与节点池容忍（T27、T28）；kind 集群 `meridian` 已按新网段重建并保留

### 2026-11-09 T13 已完成（提交 e5621ba）
- 交付：engine-spi 的 `ThreadRegistryPort`（创建子线程同一事务写 `session.thread_created`，`updateStatus` 带停止原因、写 `thread_status_*`）；engine-agentscope 的 `tools.SpawnSubagentTool`（`PlatformToolProvider` 内建，副作用取子智能体工具的最高等级）、`PlatformTool.executeAsync`、`turn.SubagentRunner`（空白构建的子上下文、预合成权限与失败关闭、独立映射器写子线程事件、终态换成 `thread_status_idle`、超时取消订阅、事件流之外的失败以错误结束）、`SubagentSlot`（本轮入口与子线程登记，`interrupt` 先中断子线程）、`SubagentOutcome`、`InterruptRelay`、`ExecutionSlot`，`middleware.SubagentQuota` 与 `PolicyMiddleware` 的每轮拉起上限；runtime 的 `session.app.DatabaseThreadRegistry`、`DatabaseEventSink` 经 `ApprovalAudit`（子线程的 `permission.decided`）、`SubagentRunner` 装配与 `meridian.runtime.worker.max-subagents-per-turn`、恢复快照只取本线程的调用、删除会话时删除全部线程的 `agent_states`；测试 `SubagentScenarioTest`、`SubagentSessionIT`（经 HTTP 的完整路径）、`SubagentRecoveryIT`；P12 报告
- 验收：9 项通过，0 项阻塞
- 决策：ADR-0040（执行组件与本轮入口、线程事件与停止原因、子线程上下文、终态与超时的停止原因、中断与拉起上限、恢复隔离、审计与删除）；架构设计升至 v1.15
- 遗留：登记事项 D41 子线程事件逐条写入的容量（R1）、D42 子线程 `session.error` 的 Webhook 语义（T17a）、D43 取消执行后的 `onErrorDropped` ERROR 日志（T27）

### 2026-11-09 R1 已完成（提交 34b708c）
- 交付：P7 容量（`scripts/perf/` 只用 JDK 的压测驱动与延迟脚本，`dev-config/agents/scripted-chat.yaml`）与 P11 内存报告；四个维度的独立审查与逐条处理（`docs/poc/R1-评审发现.md`）；修复：阻塞的供应商流与工具执行改到 `Blocking.scheduler()`（300 并发轮次吞吐 30→71.5 轮/秒）、SSE 先发 `:open`、装配关闭 `@路径` 展开并放宽工具超时到 660 秒、认证过滤器按路由首段判断并给内部接口加主体检查、`bash` 分段的绕过、产物清单 `python3 -I`、Envoy 管理接口改 Unix 域套接字、续租锁等待上限、中断与回应同批 400、被置回队列的消息轮带中断执行、子线程随父轮次结束、删除归档取锁后重读、工作区并发首次写入；回归测试与错误码约定测试、`package-info`、`meridian-arch-tests/CLAUDE.md`；`kind`、`live` profile 跳过 JaCoCo；`docs/poc/M0-总结.md`；架构设计升至 v1.16（非功能需求实测值、Q4/Q5/Q6 已决、Q2/Q3 改为 T14b/T15a 开工前）；根与模块 `CLAUDE.md` 检查
- 验收：7 项通过，0 项阻塞
- 决策：ADR-0041（M0 评审与容量测量的修正约定）；`SubagentRecoveryIT` 中把"回收后子线程仍为 running"写成期望的断言改为 `idle(interrupted)`（修正缺陷期望）
- 遗留：登记事项 D44–D54；D19 关闭，D31、D41 改为 T29，D36 改为 T28；`/doctor prompt-audit` 在执行环境中不可用，未运行

### 2026-11-09 T14a 已完成（提交 251ed4e）

- 交付：`meridian-control-plane` 应用（MVC + 虚拟线程，`cp` 迁移：tenants、tenant_settings、projects、members、role_bindings、api_keys、outbox、幂等键）；管理 API `meridian-admin-v1.yaml`（`/admin/v1/`，26 个操作，幂等、游标分页、problem+json、审计 outbox）；内部接口 API Key 校验与租户配额；`meridian-security` 的 `oidc`（控制台开发期令牌）、`rbac`（角色矩阵、`@RequiresPermission`、拦截器）、MVC 服务凭据过滤器、`ServiceRestClients`、`ControlPlaneApiKeyResolver`（60 秒缓存、`key.revoked` 淘汰）、Redis 令牌桶限流；runtime 切换到 control-plane 校验（`DevApiKeyResolver` 只在 `test`），`TenantQuotaPort` 与 `config.invalidate` 订阅；Compose `app` profile 与 Jib 镜像、`run-control-plane.sh`、`dev-api-key.sh`、`build-images.sh`、`app-smoke.sh`；架构设计 v1.17、ADR-0042
- 验收：A01–A09 全部 passing（角色矩阵 219 例；Compose 端到端吊销 0.053 秒内拒绝）
- 遗留：D55（MVC 的 API Key 过滤器与限流，T15b、T18）、D56（配额变更丢消息的对账，T27）、D57（平台管理员与 IdP 的映射，T14b）

### 2026-11-09 T14b 已完成（提交 ed57d00）

- 交付：`meridian-security` 的服务 JWT 校验（`ServiceJwtVerifier`，JWKS 缓存与未知 `kid` 刷新）与签发客户端（`ServiceTokenClient`，客户端凭据模式，过期前 60 秒刷新）、OIDC 校验（`OidcTokenVerifier`，用户标识与组声明可配置）、公开 API 接受 OIDC 令牌加 `Meridian-Project`（`ConsoleApiAuthenticator`、`ControlPlaneProjectAccessResolver`、按用户限流）、`rbac.PublicApiScopes`、内部接口按调用方限定；删除开发期服务凭据与控制台令牌；control-plane 的 `servicetoken` 上下文（`/oauth2/token`、`/oauth2/jwks`、`DirectorySigningKeys` 与轮换）、`project-access:resolve`、按组的平台管理员；`PlatformContext.userId` 与 `actor()`；Keycloak 开发 realm、Compose `auth` profile、`console-token.sh`；test-support 的 `KeycloakRealm`、`TestTokenIssuer`；出网代理的 Lua 用客户端密钥换取服务 JWT；架构设计 v1.19、ADR-0044
- 验收：A01–A05 全部 passing（角色矩阵 219 例由 OIDC 令牌驱动；两种声明配置；P14 在 kind 上以服务 JWT 复测通过）
- 遗留：D58（服务 JWT 的生产部署：密钥管理系统、多副本共享密钥、轮换触发、IdP 接入说明，T28、T26）；Compose `app-smoke.sh` 的新步骤没有在本机实跑（Docker 内存紧张）

### 2026-11-11 T15a 已完成（提交 a9db160）

- 交付：Q3 已决（OpenAI 兼容、Anthropic、Gemini）；control-plane 的 `secret` 上下文（信封加密：按租户的 AES-256 数据密钥，AES-GCM 绑定租户、凭据与版本；`KeyEncryptionService` 的本地主密钥与 Vault Transit 实现；只能写入、轮换、删除的管理 API，列表带最后使用时间与引用方；`/internal/v1/secrets:resolve` 只接受 runtime，校验租户与项目，每次解析写审计）与 `catalog` 上下文（供应商账号、模型、价格表的管理 API，价格表版本只追加、发布即生效；`/internal/v1/models/{id}`；删除被引用资源 409 带 `references`；变更发布 `config.invalidate`），迁移 `V0002`，新操作类别 `MODEL_*`、`SECRET_*`，ID 前缀 `prb`；`common.net`（`AddressPolicy` 从 runtime 移入并分类、`EndpointPolicy`）；runtime 的 `ControlPlaneModelCatalog`（缓存 60 秒、读取时再校验端点）、`ControlPlaneSecretResolver`（明文缓存 5 分钟）、`CatalogInvalidationListener`、`catalog-source`（`file` 只允许 dev、test）、模型属于租户（D23）、目录备用模型、用量消息带价格表版本；engine 的 Anthropic、Gemini 平台适配器（D25）；D52 的图片地址与端点校验；`scripts/dev/catalog-smoke.sh`；ADR-0045、ADR-0046（变更型，用户在会话中确认），架构设计 v1.20
- 验收：A01–A06 全部 passing（`SecretStoreIT`、`VaultTransitIT`、`ModelCatalogIT`、`ControlPlaneCatalogIT`、`ProviderAdaptersIT`；`-Plive` 的 `ControlPlaneCatalogLiveIT` 与真实链路 `catalog-smoke.sh --dashscope` 都通过）
- 决策：凭据属于租户、可再限定到项目；模型租户内全部项目可用；端点策略危险地址始终拒绝、私有与保留网段按配置放行（本机 fake-ip 解析到 `198.18.0.0/15`，dev 配置显式放行）；Anthropic、Gemini 不用 2.0.4 的模型类（凭据构造时给、SDK 自带重试、`boundedElastic`）
- 顺带修复：租户设置带联网搜索后端时 500（生成的 `@Size` 落在 `URI` 字段上）
- 遗留：D59（凭据库的生产部署与重新包装）、D60（Anthropic、Gemini 没有真实模型验证）、D61（联网搜索后端在 runtime 侧的校验与凭据引用，T15b）、D62（DNS 重绑定，T37）；`dev` 与 Compose `app` 的 runtime 仍读 `dev-config/providers`（开发智能体引用 `mdl_dev_*`，T15b 改由 control-plane 下发智能体后切换）

### 2026-11-12 T15b 已完成（提交 4c867bf）

- 交付：智能体规格的 JSON Schema（`meridian-api` 的 `agent-spec.schema.json`，与 `ResolvedAgentSpec` 一一对应）；control-plane 的 `agent`（草稿与发布、发布不可变、六项校验与快照、版本对比、引用与删除冲突）、`environment`（修订历史）、`skill`（JSON 技能包、frontmatter 校验、对象存储、版本只追加）、`mcp`（端点校验、MCP SDK 0.17.2 探测并保存工具清单、按工具的副作用）、`policy`（租户与项目策略、记住此决定）上下文，迁移 `V0003`，租户设置下发（D61），`config.invalidate` 新增 6 种类型，内部接口（版本与当前版本解析、环境修订、技能、权限规则、租户设置），公开 `GET /v1/agents`（`meridian-security` 的 `PublicApiAdmission` 与 MVC `ApiKeyFilter`，D55），操作类别 `AGENT_READ`、`AGENT_WRITE`，ID 前缀 `prl`，`KnowledgeCatalogClient` 的开发实现（`TODO(T18)`）；engine 的 `McpServerSpec.toolSideEffects`、`AgentEngine.evictAgentVersions`（D32）、D53 A-F5；runtime 的 `ControlPlaneAgentConfigProvider`（按项目校验，D18）、`ControlPlanePolicyStore`（D22）、`AgentConfigInvalidationListener`、`agent-config.source`（`file` 只允许 dev、test）、`ConfigUnavailableException`；test-support 的 `TestMcpServer`；`scripts/dev/agent-smoke.sh`；ADR-0047，架构设计 v1.21
- 验收：A01–A09 全部 passing（`AgentVersionIT`、`AgentResourcesIT`、`AgentConfigDomainTest`、`AgentSpecSchemaTest`、`AgentConfigSessionIT`；`-Plive` 的 `AgentConfigLiveIT` 与真实链路 `agent-smoke.sh --dashscope` 都通过，全程不读 `dev-config/`）
- 决策：一份 Schema，草稿省略发布时填写的字段；发布时快照 MCP 服务的登记配置，版本真正不可变；技能包以 JSON 上传；租户设置变更时按租户淘汰实例；解析类接口不按租户过滤，由 runtime 校验资源属于调用方项目；networknt 用 2.0.1（与 AgentScope 带来的 2.0.0 同坐标，3.x 换了 Jackson 3）
- 遗留：D53 A-F8（MCP 重连语义，T19）、D55 的 knowledge 部分（T18）、D63（`dev-config/` 只剩测试与本地开发，预算限额 T16）、D64（`TODO(T18)` 知识库存在性）、D65（已删除智能体的版本不计入引用，R2）

### 2026-11-12 T16 已完成（提交 f5de788）

- 交付：control-plane 迁移 `V0004`（`usage_records`、`audit_logs` 按月分区、只追加，分区由迁移角色拥有的 `SECURITY DEFINER` 函数创建与删除；`budgets`、`budget_usage`、`budget_alerts`、`budget_corrections`、`stream_dead_letters`），`metering`（用量消费者按价格表版本计价、幂等入账、累加预算汇总；分区维护；用量报表与公开 `GET /v1/usage`）、`budget`（预算管理与 Redis 限额同步、软阈值告警、每小时对账）、`audit`（审计消费者归一化标准字段、审计查询）上下文；操作类别 `BUDGET_READ`、`BUDGET_WRITE`、`AUDIT_READ`、`USAGE_READ`，ID 前缀 `bdg`、`bal`；persistence 的 `OutboxRelay` 认领后在事务外发布、失败不阻塞队头（D49 C-12），`StreamConsumer` 与确认后裁剪（D04）；common 的 `budget`（级别与 Redis 键）、`audit.AuditActor`；runtime 的限额改读 Redis 哈希（没有设置为 0）、计数按月、预扣记录带计数键，会话记下创建它的 API Key（rt `V0009`），联网搜索计量，审计消息补操作者与资源，outbox 新列（rt `V0008`）；`meridian-test-support` 的 outbox 契约样本；开发租户与 smoke 脚本设置预算，`app-smoke.sh` 增加零预算、用量与审计的检查；ADR-0048，架构设计 v1.22
- 验收：A01–A09 全部 passing（`UsageLedgerIT`、`UsageReportIT`、`BudgetIT`、`AuditLogIT`、runtime `BudgetLimitsWorkerIT` 与四个 outbox 契约测试，`verify.sh` 通过）；Compose 真实链路 `app-smoke.sh` 未执行（构建镜像的命令被拒绝），新步骤只做了语法检查
- 决策：预算以 token 计（价格表可能多币种、沙箱与搜索没有单价），租户、项目、API Key 按自然月、会话不分周期；每级可设默认值，没有设置为 0；限额是每租户一个 Redis 哈希、提交后增量写入加 5 分钟全量重写；对账目标是账本加未过期预扣；审计消息统一标准字段，runtime 的平铺字段并入 `details`；`group_by` 用逗号分隔的字符串（契约校验库不支持 3.1 的数组查询参数）
- 顺带修复：`OutboxIT` 中断言队头阻塞的用例改为断言新行为（D49 要求修掉该行为）
- 遗留：D66（`SubagentSessionIT` 偶发 BlockHound 失败，R2）、D67（`sessions.budget_limit` 未使用）、D68（用量明细与 CSV，T19b）、D69（死信与停止投递的 outbox 消息没有处理工具）、D70（知识检索次数与存储量的计量，T18、T19）

### 2026-11-12 T17a 已完成（提交 9aa41da）

- 交付：control-plane 的 `webhook` 上下文（迁移 `V0005` 的 `cp.webhooks`，管理接口 `/admin/v1/tenants/{t}/projects/{p}/webhooks`，签名密钥 `whsec_` 用租户数据密钥加密保存、只在创建时返回，给 runtime 的内部订阅接口与 `config.invalidate` 的 `webhook` 类型），租户配额下发 `event_retention_days`；runtime 迁移 `V0010`（`webhook_events` 订阅事件与会话事件同事务写入，`webhook_deliveries` 投递记录，`sessions` 的归档与保留列），`webhook`（投递器按项目订阅扇出、`Meridian-Signature: t=,v1=` 的 HMAC-SHA256、8 次退避与 24 小时截止、认领与至少一次、Reactor Netty 发送器复用 `EndpointPolicy` 与 `GuardedResolverGroup` 防 SSRF 与 DNS 重绑定、公开的投递记录列表、查询与重投），`retention`（`SessionPurger` 清理已删除会话的事件、线程、轮次、状态、工作区、产物、沙箱与快照、对象存储前缀并写审计 `session.purged`，`EventArchiver` 90 天无活动归档为 gzip JSON Lines，`EventRetentionSweeper` 按租户保留天数删除数据库与归档中的过期事件并写审计 `session_events.expired`），`SessionEventReader` 合并归档与数据库透明读取（列表与 SSE 补发）；`SandboxDriver.deleteSessionSandboxes` 按会话标签删除 kind 中的 claim；删除与归档撤销出网授权，已删除会话不再获取沙箱；ID 前缀 `whk`、`whd`；ADR-0049，架构设计 v1.23
- 验收：A01–A05 全部 passing（`WebhookDeliveryIT`、`HttpWebhookSenderTest`、`WebhookAdminIT`、`SessionPurgeIT`、`SessionPurgeKindIT`（kind 集群 `meridian`）、`EventArchiveIT`、`EventRetentionIT`、审计契约测试，`verify.sh` 通过）
- 决策：只推送主线程与会话级的事件（D42）；投递经 outbox 解耦，接收方按事件 ID 去重；清理等待该会话的投递至多 12 小时；归档按会话一个文件、再次归档合并后删除旧文件，读取在可重复读的只读事务中取指针与数据库事件；保留清理按会话记下已清理的截止时间，避免反复扫描；Webhook 管理复用 `API_KEY_READ`、`API_KEY_WRITE` 两个操作类别
- 顺带修复：runtime 测试的 BlockHound 放行 `VirtualThread#submitRunContinuation`（事件循环唤醒虚拟线程时 `ForkJoinPool` 竞争下短暂 park，被拦截后虚拟线程永不再运行，`WebhookDeliveryIT` 偶发永久挂起）；测试 PostgreSQL 的 `max_connections` 调到 300（缓存的 Spring 上下文增多后连接用尽）
- 遗留：D37 存活会话的无主 claim（T28）、D48 与 D49（R2）、D50 C-10 其余（R2）与 S-F4（T37）、D71（归档整份读入内存，T28）、D72（签名密钥轮换与投递记录的时间过滤，T19b）、D73（清理与删除通知投递的先后，R2）；kind 集群 `meridian` 保留给 T28、T29（不用时 `kind delete cluster --name meridian` 释放内存）

### 2026-11-12 T17b 已完成（提交 b7df25f）

- 交付：文件资源（runtime 迁移 `V0011` 的 `rt.files`，`file` 上下文：`POST /v1/files` 的 multipart 上传、Tika 4.2.0 按内容检测媒体类型、文件名规范化、大小上限 `meridian.runtime.files.max-size` 同时限制 multipart；`GET /v1/files/{id}`、`/content` 带检测类型与 `attachment`；`files:write` 映射到 `SESSION_WRITE`）；引用：`FileReferenceResolver` 在发送事件时校验引用属于本项目、补全文件名与检测类型，引用文件的消息按 queue 投递；`TurnFileStager` 在调用内核前把文件放到 `uploads/{file_id}/{filename}`（none 档 `WorkspaceStorePort`，sandbox 档新增的 `SandboxDriver.writeFiles`），`TurnContext.workspaceFiles` 交给内核翻译为工作区路径（D07）；工作区 API（`GET/PUT …/workspace/files/{path}`、`workspace:list`，none 档读写与智能体同一份存储，sandbox 档只读 `outputs/` 副本，受保护路径 403，路径规则 400，`workspace_entries.content_size`）；`session.usage`（`rt.turn_usage` 与 outbox `usage` 同事务累加，`TurnFinisher` 在 idle、requires_action 的终态之前写本轮、按模型与会话累计）；`POST /v1/responses` 子集（`ResponsesMapper`，与对话补全共用 `ModelInvoker`、首信号与聚合）；engine-spi 的 `WorkspaceFiles`；ADR-0050，架构设计 v1.24
- 验收：A01–A05 全部 passing（`FileReferenceWorkerIT`、`WorkspaceApiIT`、`FilesApiIT`、`SessionUsageIT`、`ModelEndpointsIT`，`SessionApiContractIT` 覆盖 24 个操作，`verify.sh` 通过）
- 决策：文件在轮次开始时放置而不是上传时；路径经 `TurnContext` 交给内核，不写进事件；steer 中的文件降级为 queue；`session.usage` 的 `model_id` 改为可选（事件从未产生过，openapi-diff 判为兼容）；不支持的 Responses 参数在 schema 中列出以便逐项报错；D26 维持子集
- 顺带修复：开工基线 `EventArchiveIT` 偶发失败（SSE 与列表接口的 `created_at` 小数位表示不同，按时刻比较，表示的不一致登记 D76）；D54 的重复实现收口（Q-M1、Q-M2、Q-L1、Q-L3）
- 遗留：D74（上传文件没有删除与保留期）、D75（按 ID 引用的图片不作为视觉输入）、D76（时间的文本表示）、D77（事件中的 delivery 是请求值）；D66 在本任务的全量检查中又出现一次（`SubagentSessionIT` 的请求 ID 生成被 BlockHound 判为阻塞，重跑通过）

### 2026-11-12 T18 已完成（提交 51dc16a）

- 交付：`meridian-knowledge`（迁移 `kb/V0001`：知识库、文档、写入作业、切片（HNSW、`tsv` 与 `acl_tags` 的 GIN）、`storage_reports`、outbox、幂等键；公开 API `KnowledgeBases` 8 个操作与内部接口 `GET /internal/v1/knowledge-bases/{id}`；写入流水线：`SKIP LOCKED` 认领与租约、按尝试次数 fencing、退避重试 3 次、Tika/PDFBox/Markdown/doc-parser 的解析路由、中间表示与切分器、经 runtime 的批量 Embedding；每日存储计量、审计、幂等、`key.revoked` 订阅）；`meridian-model-client`（`EmbeddingClient`）；`doc-parser/`（FastAPI + Docling 2.144.0 与 RapidOCR，MinerU 可选，镜像固化模型与中文字体，4.12 GB）；control-plane 的 `KnowledgeServiceCatalog`（进程内签发自己的服务 JWT）、`knowledge_storage_gb_days`（cp `V0006`）；security 的 `KNOWLEDGE_READ`、`KNOWLEDGE_WRITE`；common 的 `chk`、`ijb`、`TextTokens.approximate`、`FileNames`（从 runtime 移入）；Compose `app` profile、`run-knowledge.sh`、`run-doc-parser.sh`、`build-images.sh`；ADR-0051，架构设计 v1.25
- 验收：A01–A10 全部 passing（`IngestionIT`、`ChunkIndexesIT`、`KnowledgeApiIT`、`StorageMeteringIT`，容器化的 doc-parser；`-Plive` 的 `IngestionLiveIT`；`verify.sh` 通过）
- 决策：向量维度用 Flyway 占位符并在启动时核对；PDF 先用 PDFBox 检查，扫描件与有框线表格的交给 doc-parser；结束事务锁住文档与本次尝试再整体替换切片；存储量每天按执行时的大小上报并用 `storage_reports` 去重；doc-parser 不校验服务 JWT、靠网络策略；`acl_tags` 用逗号分隔的表单字段（Spring 解析不了数组表单字段）
- 开工基线：`EventStreamIT` 在主机负载约 100 时连续失败，负载降下来后的全量运行中通过（时序敏感）；收尾时 D66 的 `SubagentSessionIT` 又出现两次
- 遗留：D78（知识库 ID 没有进入用量账本）、D79（doc-parser 的网络策略与资源限额）、D80（PDF 路由的启发式、P8 复核）、D81（存储量取执行时的大小、知识库没有删改接口）；本机 `.env` 需要 `MERIDIAN_KNOWLEDGE_CLIENT_SECRET` 才能用 Compose 起 knowledge（`run-*.sh` 会取 `.env.example` 的值）

### 2026-11-12 T19 已完成（提交 c87dc78）

- 交付：knowledge 的 `search` 上下文（向量与全文并行召回、过滤下推到 SQL、HNSW 迭代扫描、全文词项"或"查询、每个 Embedding 模型一路、RRF k = 60、rerank 前 20 个候选、查询改写失败回退原查询、`knowledge_search_calls` 计量），知识库的 `rerank_model`（kb `V0002`）；公开 `POST /v1/knowledge-bases/{id}/search`、内部 `POST /internal/v1/knowledge-bases:search`、MCP Server `/mcp/knowledge`（`McpApiKeyFilter`，标签取 `Meridian-Acl-Tags`）；model-client 的 `RerankClient`、`ChatClient`；runtime 的 `/internal/v1/chat/completions`、`HttpKnowledgeSearch`（`meridian.runtime.knowledge.url`）；engine 的 `KnowledgePrefetchMiddleware`（150）与 `McpSession`（服务重启后重建客户端，D53 A-F8）；智能体规格的 `knowledge.query_rewrite_model`；control-plane 的 cp `V0007` 与发布校验；检索评测语料、录制与记录 `docs/engineering/retrieval-evaluation.md`；ADR-0052，架构设计 v1.26
- 验收：A01–A07 全部 passing（`KnowledgeSearchIT`、`KnowledgeMcpIT`、`RetrievalEvalIT`、`KnowledgeSessionIT`、`KnowledgePrefetchScenarioTest`；`-Plive` 的两个评测录制类与 `KnowledgeQaLiveIT`；MCP Inspector 2.13.0 调通，P8 通过；`verify.sh` 通过）
- 决策：rerank 模型挂在知识库上、改写模型挂在智能体配置上；不限标签的切片对所有人可见；MCP 的标签放请求头不放工具入参；检索评测拆成两个录制类以守住每类 20 次真实调用；端到端问答用 knowledge 的 Jib 镜像容器与本进程 runtime
- 开工与收尾：基线与收尾的全量检查各有一次 BlockHound 判定单调 ULID 生成器 park 为阻塞（基线 `SubagentSessionIT`、收尾 `EventStreamIT`，都是 D66 的根因），单独重跑通过
- 遗留：D82（MCP 服务端会话在进程内、没有过期，T28、R2）；D78、D80 改到 R2；查询改写只看单条消息

### 2026-11-13 T19b 已完成（提交 695b7e3）

- 交付：页面到接口的对照表 `docs/engineering/console-api-map.md`（应用外壳与十一个一级导航）；runtime：会话的 `agent_id` 与列表筛选、`GET /v1/session-stats`（部分索引现读计数，失败率读 `TurnStatsRollup` 每分钟按水位汇总的 `agent_turn_stats`）、项目级 `GET /v1/pending-actions`（带智能体、处理人、结论）、投递记录的时间范围，rt `V0012`；security：作用域 `actions:respond`（审批人、所有者，只能发回应事件）、OIDC 的已验证邮箱、`ApiEnumConverterFactory` 移入并给 MVC 注册；control-plane：`/admin/v1/me`、`/me/access`，按邮箱邀请成员与首次登录绑定（cp `V0008`），项目预算消耗 `budget-status`，用量明细与 CSV 导出，供应商账号连通性测试与 MCP 的 `last_check`（cp `V0009`），Webhook 签名密钥轮换；knowledge：知识库的修改与删除；engine：工具确认带出建议规则、回应时 `remember_rule`（D21）；ADR-0053，架构设计 v1.27
- 验收：A01–A06 全部 passing（`ConsoleQueriesIT`、`MemberInvitationIT`、`UsageReportIT`、`BudgetIT`、`ConnectivityIT`、`WebhookAdminIT`、`KnowledgeApiIT`、`RoleMatrixIT` 813 例、`verify.sh` 通过）
- 决策：概览的失败率用后台小时汇总、不在轮次结束时累加；审批人经新作用域走事件回应而不另开回应接口；OIDC 成员按已验证邮箱绑定、不调 IdP 的专有接口；当前用户的权限由服务端按角色矩阵算出；建议规则由内核按工具重算（2.0.4 的 `ReActAgent` 丢掉了权限结论上的建议）；删除知识库不检查智能体版本的引用
- 收尾时修复：测试类自己的开发期 Key 与新增的审批 Key 下标冲突（`ApiTestKeys.FIRST_FREE_KEY_INDEX`）；`MeApiController` 依赖 `infra.system` 的记录（移到 `member.domain`，ArchUnit 规则 5）
- 顺带修复：MVC 服务的枚举查询参数按 Java 常量名转换（`level=project`、`kind=model_tokens` 会 400）
- 遗留：D83（删除知识库不检查引用，R2）、D84（Webhook 密钥轮换没有过渡期，T26）、D85（之前的会话没有 `agent_id`，T28）；D74 文件删除改给 R2

### 2026-11-13 R2 已完成（提交 a6a2c04）

- 交付：评审报告 `docs/plan/R2-后端评审.md`（资源表与一期能力清单的实现位置、契约覆盖、对照表核对、四个维度 72 条发现的逐条结论、分给 R2 的登记事项的结论）；契约覆盖 `meridian-test-support` 的 `contract.ContractCoverage` 与三个服务的 `ContractCoverageIT`（公开 40、管理 107、内部 21 个操作）；验收预演 `e2e/backend/`（`env.sh`、`m1_backend.py`、场景脚本）；修复：所有者邀请改邮箱需要 `OWNER_ROLE_MANAGE`、MCP 引用租户级凭据需要 `SECRET_WRITE`、敏感写操作的幂等记录加密与凭据值 HMAC 指纹、outbox 瞬时错误不计次数（`TransientFailures`）、审批人不能记到项目策略、公开 API 幂等主体加项目、bash 异常也写审计、会话清理的等待与按会话推迟（rt `V0013`，删除 `budget_limit`）、用量记录重试与照常结算、Webhook 截止时间、外部游标 400、探测说明只给分类、`/me/access` 校验项目、JSON 请求体 10 MB、上传超限 `file.too_large`、首次带自定义工具创建会话的 500、脚本化模型服务运行中加载新脚本；ADR-0054，架构设计 v1.28
- 验收：A01–A06 全部 passing（`verify.sh` 收尾通过；`-Plive` 6 个 LiveIT、`-Pkind` 5 个 KindIT 通过；`e2e/backend` 八个场景与 `--live` 三个场景通过）
- 决策：I-02、I-08、I-11、A-11、A-17、A-20、A-23、A-25、A-26、C-12、C-13 不同意（理由见报告）；D65、D73 维持原决定；D54 Q-M3 维持现有分层约定；`-Plive` 不运行两个会重写录制的检索评测类
- 开工基线：`EventStreamIT` 失败一例，即 D66（BlockHound 把单调 ULID 工厂的锁判为阻塞），修复后重跑通过
- 遗留：D86（API Key 最后使用时间，需要你决定是否插入补充任务或并入 T25b）、D87–D94（文件删除、知识库用量归属、MCP 无状态传输、知识库删除的引用检查、API 契约收尾 T26、安全加固 T37、部署 T28、作业健壮性 T27/T28/T29）

### 2026-11-13 T21 已完成（提交 5220288）

- 交付：console 的工程基座——OIDC 授权码加 PKCE（oidc-client-ts，令牌只在内存，refresh token 后台续期，刷新页面后 iframe 静默登录，运行时配置 `/config.json`）；TanStack Router 路由树（`/projects/$projectId/` 下十一个一级导航各一个路由模块，空态；搜索参数 `key=value` 与重复键；FilterBar 改用 `useSearchFilters`）；正式应用外壳（`app/shell`：导航收起记忆、项目切换、面包屑、⌘K、账号菜单与主题、与服务端断开与预算告警横幅）；openapi-fetch 客户端（`adminApi`、`v1Api(projectId)`、`unwrap`、`authorizedFetch`，管理 API 类型由 `redocly.yaml` 生成并接入构建）；TanStack Query 统一配置与变更失败的错误通知（`notifyError`，请求 ID 可复制）；`DataRegion`（区域级 Suspense，骨架 150ms 后出现、至少 300ms）；SSE 客户端的乱序暂存与跳号重连补发；`usePermission` 接 `/admin/v1/me/access`；页面模板移到 `components/templates`，原型外壳改为正式外壳的适配器；Playwright：`e2e/app/`（登录、导航、主题、权限，连接 Compose 后端与 Keycloak）与外壳、四种模板的截图基线（darwin 与 Linux 各 24 张）；`scripts/dev/console-e2e-env.sh`、`console-playwright-linux.sh`；CI 的 `console` 作业；ADR-0055，ADR-0021 追加四个依赖，架构设计 v1.29
- 验收：A01–A09 全部 passing（`e2e:app` 本机 16 passed、Linux 镜像 12 passed；截图基线本机与镜像各 24 passed，换上正式外壳后与 T20c 截图逐像素比较外壳与模板 27/28 一致、差异只在命令面板内容；SSE 10 个用例含 300 事件的随机混合；a11y 亮暗各 227 个故事零违规；`verify.sh` 通过）
- 决策：ADR-0055（刷新后用 iframe 静默登录而不把令牌写进存储；运行时配置；项目放在路径里；搜索参数格式；外部路由补上三个 runtime 接口；模板单独一层；截图基线按平台分开并在官方镜像中生成）；不用 TanStack Router 的文件路由；Zod 留给 T22；401 视为可重试（认证模块同时续期）；空态标题平衡换行
- 遗留：D95（控制台的部署，T28）、D96（CI 的 verify 作业缺 uv，CI 尚未在 GitHub 上实跑）、D97（命令面板的智能体与最近会话，T22、T24）

### 2026-11-13 T22 已完成（提交 1cf2761）

- 交付：console 的智能体（列表、详情的概要、版本、会话、子智能体四个标签页、编辑页九个分组、版本对比、基于任一版本创建草稿、删除）与环境（列表、新建与编辑、出网白名单、修订历史与修订详情、删除）；规格的 Zod schema 与 agent-spec.schema.json 逐字段比对、跨字段规则同发布校验、本地与服务端错误经同一套字段锚点定位；Monaco 提示词编辑器（本地 worker，只打包 Markdown）；可搜索的资源选择器带状态；按工具提示语法的权限规则编辑器；xyflow 子智能体拓扑；领域组件 FormField、LeaveGuard；面包屑的动态名称（`lib/crumbs.tsx`）；DetailPage 受控标签页；⌘K 加入智能体（D97）；智能体与环境按路由懒加载（首屏 gzip 约 219 KB）；端到端后端 `console-e2e-env.sh` 起 knowledge、runtime 读 control-plane（compose 变量默认仍是 file），全局准备建模型、技能与知识库；Playwright `agents`、`environments`、`a11y-agents`、`evidence-agents` 四个用例文件；删除智能体编辑原型
- 修复：knowledge 的公开 API 不接受控制台用户（meridian-security 的 MVC 分支补上经 control-plane 的项目权限，151af58）；`InertOutside` 在关闭的选择器里常驻、对话框关闭后外壳仍是 inert（bbb8841）；FilterBar 测试的时序（b53a602）；会话统计 `active_sessions` 的说明（删除智能体后会话继续用已绑定的版本）
- 验收：A01–A08 全部 passing（`e2e:app` 21 passed；评审截图 82 张在 `tmp/evidence/T22/`；Storybook 基线与 a11y 82 passed；真实页面 axe 零违规；ego-browser 走查发现并修正 6 处；`verify.sh` 通过）
- 决策：表单值就是规格本身（snake_case），快照字段读入时去掉；校验不走 resolver，自写锚点映射（没有引入 @hookform/resolvers）；草稿参与对比时不显示快照字段、版本 ID 一律不显示；删除后果按 runtime 的实际行为写；Monaco 只经 0.57 的公开入口装配；面包屑的对象名由路由组件登记
- 遗留：D98（会话行跳转随 T24，修订人与发布人只有主体标识）、D99（编辑页模板的分组高亮阈值，R3）；D97 的会话部分随 T24；ego-browser 有新版本可升级（未升级）

### 2026-11-13 T23 已完成（提交 4b1fad4）

- 交付：控制台的能力（技能列表、浏览器端解析与预览的技能包上传、技能详情的版本、文件与被引用；MCP 服务的登记、详情、连接状态、逐个工具的副作用标注与重新探测；自定义工具的说明）、模型（供应商账号与就地写入凭据、行内连通性测试；模型目录与经直通接口的模型测试；价格表与版本、发布新版本）、设置的"凭据"标签页（写入、轮换、删除，明文只写不读）；领域组件 `ReferenceList`/`InUseDialog`，`ResourceSelect` 移到领域组件；连通性的状态文字进 `labels.ts`；`formatDateTime`、`formatTokenLimit`；依赖 `yaml` 2.9.1；管理 API 的 `Skill.references`（control-plane `SkillView`、`AgentVersionRefRepository.referencingAll`）；脚本化模型服务回应 `GET /v1/models`；Playwright `capabilities`、`models`、`models-live`（@live，`E2E_LIVE=1`）、`secrets`、`a11y-capabilities`、`evidence-capabilities`
- 修复：表单内的"写入凭据"按钮默认提交外层表单；上传新版本后文件清单停在旧版本；浏览器把保存的登录名与密码自动填进凭据对话框（`autocomplete="new-password"`，走查发现）；选择器的 `aria-label` 与可见文字不符、资源选择器加载状态的 `aria-label` 无角色（新故事暴露）
- 验收：A01–A08 全部 passing（`e2e:app` 31 passed、live 1 passed；截图 `tmp/evidence/T23/` 68 张页面加 18 张状态，三轮评审；外壳与模板截图基线 24 passed；Storybook a11y 与真实页面 axe 亮暗零违规；ego-browser 走查发现并修正 2 处；`verify.sh` 通过）
- 决策：ADR-0056（技能带引用方；模型测试经直通接口、连通性测试不产生用量；凭据写入不经变更缓存；设置页改为标签页；页面的标签页在搜索参数）；架构设计 v1.30；ADR-0021 追加 `yaml`；没有手写 YAML 解析，不支持 zip 技能包
- 遗留：D100（模型、供应商账号、MCP 服务没有引用方字段，R3 决定）、D101（端到端数据只增不减）；Linux 截图基线没有重新生成（T23 未改外壳与模板，darwin 基线 24 passed）；ego-browser 有新版本可升级（未升级）

## 计划变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-11-07 | v1.0 | 初版：48 个任务（T01–T42、R1–R6），370 个验收项 |
| 2026-11-07 | v1.1 | 对抗评审后修订：拆分 T09、T10、T12、T14、T15、T17、T20、T25 为带字母后缀的子任务，新增 T19b（控制台所需的后端接口），R2 回到纯评审；T20a 增加视觉方向关口，控制台设计轨道改在 worktree 中并行；T31、T32、T34、T35、T37 增加设计关口；补上租户并发配额、脚本化模型服务、提示词分层与来源标签、docx 内置技能、事件保留、知识库计量等无人负责的条目；前端验收改为可量化标准。共 58 个任务、417 个验收项 |
