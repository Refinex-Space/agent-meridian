# T12a 会话沙箱生命周期、bash 与产物

| 项 | 内容 |
|---|---|
| 阶段 | 1 M0 内核与运行时核心 |
| 依赖 | T11 |
| 推荐配置 | Opus 5.5，effort xhigh，新会话 |
| 规模 | 大 |
| 关联验证项 | P4（`sandbox` 档）、P6（bash 规则）、P9 |

启动方式：在仓库根目录开新会话，输入 `/task T12a`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；AgentScope 只以 2.0.4 为准（读法见 `docs/plan/references.md`）。
- 只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

T11 提供了沙箱的底层能力。本任务把它变成会话级能力：一个会话一个沙箱，跨轮次存活，主线程与子线程共享，空闲后打快照释放；智能体通过 `bash` 工具在沙箱里执行命令；产物同步到对象存储；技能的脚本被投影进沙箱。沙箱的出网控制在 T12b。

验收中的隔离与生命周期都必须在本机 kind 集群上实测。

## 必读

1. 架构设计「工作区与沙箱」全节，「运行时架构」中「引擎装配」的文件系统行、「平台工具」的 `bash` 行。
2. T11 的模块 `CLAUDE.md` 与 `docs/poc/P05-沙箱与预热池.md`。
3. 实现时查阅（AgentScope 2.0.4 源码，按需定位，不通读）：`HarnessAgent.Builder` 的 `filesystem(...)` 与 `filesystemRoute(...)`（与 `abstractFilesystem` 互斥）、`RoutedSandboxFilesystem`、`SandboxLifecycleMiddleware`、`SandboxManager`（外部沙箱的优先级、外部沙箱绕过 `SandboxExecutionGuard`）、`SandboxContext.builder().externalSandbox(...)`、`SandboxFilesystemSpec.workspaceProjectionEnabled`。

## 范围

### 要做

- `sandbox` 上下文的 `SandboxService`：首次需要时获取、状态与 Pod IP 落库、每轮注入外部 `SandboxContext`、空闲回收、产物同步、技能资源投影。
- `sandbox` 档的文件系统装配：`filesystem(KubernetesFilesystemSpec)` 加 `filesystemRoute`（受保护路径与 `large_tool_results/`），关闭默认工作区投影。
- `bash` 工具：规则内容为命令前缀，副作用等级 `non_idempotent`，输出截断。
- 内置技能 `skills/builtin/docx/`：作为技能投影的测试技能，也是 M1 场景 2 的依赖。
- 计量：沙箱 vCPU 秒写入用量；沙箱命令写入审计。
- 指标：`meridian_sandbox_claim_seconds`。
- M0 验证 P4（沙箱档）、P6（bash 规则）、P9。

### 不做

- Envoy 代理、`ext_authz`、出网白名单、NetworkPolicy（T12b）。本任务的沙箱沿用 T11 的网络配置。
- 子智能体（T13）。本任务用两个线程手工构造的场景验证子线程共享沙箱。
- Web 终端（M2）。

## 交付物

### 1. `SandboxService`

严格按架构设计「会话沙箱」各条实现：

- **获取**：会话第一次需要沙箱时经 `SandboxDriver` 从环境规格对应的预热池认领；状态写 `sessions.sandbox_state` 与 `sandbox_pod_ip`。
- **注入**：每轮由适配层以 `SandboxContext.builder().externalSandbox(...)` 放入 `RuntimeContext`；外部沙箱用 `SingleStartSandbox` 包装。
- **空闲回收**：轮次开始时更新 `sandbox_last_used_at`；回收器找出空闲超过 10 分钟（可配置）的会话，在 `sessions` 行锁内确认没有 `queued`、`running`、`awaiting_action` 的轮次，再快照、删除沙箱、清空 Pod IP。下一轮需要时从快照恢复。回收器的扫描仓储继承 `SystemRepository`，放在 `infra.system`。
- **产物**：`outputs/` 在每轮结束时同步到对象存储 `rt/outputs/{tenant}/{project}/{session}/`。
- **技能资源**：沙箱创建或恢复后，用 `execute` 加 tar 写入 `/workspace/.meridian/skills/`；每轮开始时校验哈希，不一致就重写。
- **删除会话**：删除沙箱与全部快照、产物（会话删除的总流程在 T17a，本任务提供沙箱部分的方法）。

### 2. `bash` 工具

- 继承 `PlatformTool`，只在 `sandbox` 档注册。
- 规则内容为命令前缀，例如 `bash(git status:*)`；复合命令（`&&`、`||`、`;`、`|`）的每一段都要匹配才算匹配（参考 Claude Code 的机制，只学机制）。
- 输出截断（例如 30000 字符，常量注明理由），超时可配置。

### 3. 内置技能 docx

`skills/builtin/docx/`：`SKILL.md` 加 python-docx 脚本（沙箱镜像已装 python-docx），功能是把 Markdown 内容生成 docx 到 `outputs/`。用 `ScriptedModel` 跑通"加载技能、在沙箱中运行脚本、产物同步到对象存储"。T15b 的技能包上传测试复用它。

### 4. 验证

| 验证项 | 做法 |
|---|---|
| P4 沙箱档 | 沙箱档会话中 `bash` 可用；写受保护路径被拒绝；`large_tool_results/` 进入平台存储；runtime 本地磁盘无会话文件 |
| P6 bash 规则 | `bash(git status:*)` 自动放行；其他命令触发审批；复合命令中任一段不匹配就触发审批 |
| P9 | 两轮之间沙箱不停止；子线程（手工构造）能读到主线程写的文件；技能资源存在于沙箱中且被篡改后下一轮恢复 |

## 实现要点

- 回收器与轮次开始之间存在竞争：用行锁与"无活动轮次"条件在同一事务中判定，并写测试制造这种竞争。
- `kind` 标签的测试需要集群运行；集群不存在时，测试前先运行 `scripts/dev/kind-gvisor.sh meridian`。启动前检查 Docker 可用内存，不足时按协议提醒用户。

## 验收标准

- [T12a-A01] 沙箱档会话：智能体用 `bash` 执行命令、读写文件成功；沙箱跨两轮存活。验证：`kind` 集成测试（`ScriptedModel` 驱动）。
- [T12a-A02] 空闲回收：超过空闲阈值后沙箱被快照并删除；下一轮从快照恢复，文件一致；回收与新轮次并发时不会删除正在使用的沙箱。验证：集成测试（可控时钟）。
- [T12a-A03] P4 沙箱档的四条全部满足。验证：测试；补全 `docs/poc/P04-文件系统装配.md`。
- [T12a-A04] P6：bash 规则的三种情况全部符合预期。验证：测试；补全 `docs/poc/P06-规则与审批.md`。
- [T12a-A05] P9 的三条全部满足。验证：测试；报告 `docs/poc/P09-会话沙箱.md`。
- [T12a-A06] `outputs/` 在轮次结束后出现在对象存储中；docx 技能生成的文件可以下载并被 python-docx 重新打开。验证：集成测试。
- [T12a-A07] 沙箱 vCPU 秒进入用量记录，沙箱命令进入审计。验证：集成测试。
- [T12a-A08] `scripts/dev/verify.sh` 通过；`-Pkind` 的测试在本机通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 更新 `meridian-runtime`、`meridian-engine-agentscope`、`sandbox-runtime` 的 `CLAUDE.md`。
- 更新 `docs/poc/README.md` 中 P4、P6、P9 的状态。
