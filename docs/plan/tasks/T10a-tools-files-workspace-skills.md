# T10a 平台工具基类、文件工具、工作区存储与技能

| 项 | 内容 |
|---|---|
| 阶段 | 1 M0 内核与运行时核心 |
| 依赖 | T09b |
| 推荐配置 | Opus 5.5，effort high，新会话 |
| 规模 | 大 |
| 关联验证项 | P4（`none` 档）、P6（文件工具规则） |

启动方式：在仓库根目录开新会话，输入 `/task T10a`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；AgentScope 只以 2.0.4 为准（读法见 `docs/plan/references.md`）。
- 只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

智能体通过工具做事。架构设计规定平台自己实现文件、Web、知识检索等工具（ADR-0008），因为 harness 的内置工具不支持内容级权限规则。本任务实现所有平台工具共用的基类与工具账本、六个文件工具、`none` 档的工作区存储、受保护路径、技能仓库和 `PolicyMiddleware`。Web 工具、知识检索工具、MCP 客户端、提示词分层在 T10b，它们都建立在本任务的基类之上。

工具描述直接决定模型会不会正确使用工具，写工具描述时参考 `docs/plan/references.md` 中关于工具描述的指引。

## 必读

1. 架构设计「运行时架构」：「引擎装配」（文件系统、`disable*`、`skillRepository` 行）、「平台中间件」（`PolicyMiddleware`）、「平台工具」（全节，含保留工具名）、「崩溃恢复」中副作用等级与幂等键的用法；「工作区与沙箱」中的「文件系统装配」。
2. `docs/plan/references.md`：工具描述的写法参考 spring-ai-agent-utils 与 pi；权限规则匹配与"先读后改"参考 claude-code-analysis（只学机制）。
3. 已有模块 `CLAUDE.md`。
4. 实现时查阅（AgentScope 2.0.4 源码，按需定位，不通读）：`ToolBase`（`matchRule`、`checkPermissions`、元数据）、`PermissionEngine` 调用 `matchRule` 的位置、`BaseStore`（尤其 `putIfVersion` 默认返回 false）、`RemoteFilesystem`、`CompositeFilesystem`、`AbstractFilesystem`、`NamespaceFactory`、`RuntimeContextSkillRepository`。

## 范围

### 要做

- 平台工具基类 `PlatformTool`（继承 `ToolBase`）：副作用等级、`matchRule` 规则语法、工具账本集成、幂等键、指标、审计。
- 文件工具：`read_file`、`list_files`、`glob_files`、`grep_files`、`write_file`、`edit_file`。
- `MeridianBaseStore`、`ReadOnlyFilesystem`、`none` 档的 `CompositeFilesystem` 装配、受保护路径的内容生成。
- `MeridianSkillRepository`。
- `PolicyMiddleware`（拉起数量上限留给 T13）。
- 启动自检：Toolkit 中的非 `ToolBase` 工具必须在白名单内。

### 不做

- `web_search`、`web_fetch`、`knowledge_search`、MCP 客户端、提示词分层与来源标签（T10b）。
- `bash` 与沙箱档文件系统（T12a）、`spawn_subagent`（T13）。
- control-plane 中技能的注册（T15b）。本任务从 `AgentConfigProvider` 的文件实现读取。

## 交付物

### 1. `PlatformTool` 基类

- 子类声明名称、描述、参数 schema、副作用等级、规则内容的匹配方式（路径 glob、命令前缀、域名、资源 ID）。
- 执行前写工具账本（`pending`，幂等键为 `session_id` 加 `tool_use_id`），执行后更新为成功或失败并保存结果引用；`non_idempotent` 工具把幂等键传给下游。
- 统一的指标（`meridian_tool_calls_total`）、span（与 `ToolSpanMiddleware` 配合）、审计事件。
- 文件和命令操作一律经 `HarnessAgent.getWorkspaceManager().getFilesystem()` 拿到的文件系统执行，并传入 `ToolCallParam.getRuntimeContext()`。
- 新增一个工具只需要继承基类、写描述与 schema、实现执行逻辑；这条路径写进模块 `CLAUDE.md`，T10b、T12a、T13 都按它新增工具。

### 2. 文件工具

规则内容为路径 glob；`edit_file` 要求先读后改（参考 Claude Code 的读取状态机制，只学机制）；输出大小有上限，超出时截断并告诉模型如何分段读取。

工具描述用英文写给模型（模型对英文工具描述的遵循度更稳定），代码注释用中文；描述中写清用途、何时使用、参数含义、返回格式、限制。

### 3. 工作区存储

- `MeridianBaseStore` 实现 `BaseStore`：命名空间由 `NamespaceFactory` 生成，格式 `[tenant_id, project_id, end_user_id, session_id]`，四个值从 `PlatformContext` 取，**不**取 `RuntimeContext.getSessionId()`；64 KB 以内的内容存 `rt.workspace_entries`，更大的存对象存储；**必须**覆盖 `putIfVersion`；`search` 支持分页。
- `ReadOnlyFilesystem` 实现 `AbstractFilesystem`：读取委托给内容提供者，写操作一律返回失败结果。
- 受保护路径的内容由平台根据 `ResolvedAgentSpec` 生成：`AGENTS.md`（系统提示词）、`skills/`（技能清单）、`subagents/`、`tools.json`、`MEMORY.md`（一期为空）。
- `none` 档用 `abstractFilesystem(CompositeFilesystem(default = RemoteFilesystem(MeridianBaseStore), routes = { 受保护路径 → ReadOnlyFilesystem }))` 装配。

### 4. 技能

`MeridianSkillRepository` 实现 `RuntimeContextSkillRepository`，按租户、项目、智能体解析技能。本任务的技能来源是 `AgentConfigProvider` 的文件实现（`dev-config/skills/`）。验证技能清单出现在提示词中、`load_skill_through_path` 能加载正文。

### 5. `PolicyMiddleware`

工具必须在智能体允许的工具集内；非 `ToolBase` 工具只放行 `load_skill_through_path`，`wait_async_results` 直接返回合成的 DENIED 结果。

## 实现要点

- `matchRule` 的语义要与权限引擎的调用方式一致：规则内容为空时匹配该工具的全部调用。
- 工具账本的写入同样受租约约束（经 T05 的事件与账本写入入口）。
- P6 的文件工具部分：用 `ask` 与 `deny` 规则覆盖路径 glob 的匹配、受保护路径的拒绝；结论写入 `docs/poc/P06-规则与审批.md`（文件工具部分；文件不存在时创建，T12a 补 `bash` 部分）。

## 验收标准

- [T10a-A01] 所有平台工具继承 `PlatformTool`；启动自检在出现白名单外的非 `ToolBase` 工具时启动失败。验证：测试。
- [T10a-A02] 文件类工具的路径规则：`read_file(reports/**)` 放行匹配路径、其他路径按默认处理；`write_file` 写受保护路径被拒绝；`edit_file` 未读先改被拒绝。验证：集成测试；P6 报告的文件工具部分。
- [T10a-A03] P4（`none` 档）：完整跑一轮读写文件的会话后，runtime 节点本地磁盘上没有任何会话文件；大文件进入对象存储，小文件进入 `workspace_entries`。验证：集成测试；报告 `docs/poc/P04-文件系统装配.md`（`none` 档部分）。
- [T10a-A04] 工具账本：每次调用都有记录，幂等键为 `session_id` 加 `tool_use_id`，`non_idempotent` 工具把幂等键传给下游。验证：集成测试。
- [T10a-A05] 技能清单出现在系统提示词中，`load_skill_through_path` 能加载技能正文。验证：场景测试。
- [T10a-A06] `scripts/dev/verify.sh` 通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 更新 `meridian-engine-agentscope`（工具基类、新增工具的步骤、规则语法）与 `meridian-runtime` 的 `CLAUDE.md`。
- 更新 `docs/poc/README.md` 中 P4、P6 的状态。
