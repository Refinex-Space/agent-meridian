# T15b 智能体版本、环境、技能、MCP 与配置分发

| 项 | 内容 |
|---|---|
| 阶段 | 2 M1 控制面与治理 |
| 依赖 | T15a |
| 推荐配置 | Opus 5.5，effort high，新会话 |
| 规模 | 大 |
| 关联验证项 | 无 |

启动方式：在仓库根目录开新会话，输入 `/task T15b`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；AgentScope 只以 2.0.4 为准（读法见 `docs/plan/references.md`）。
- 只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

runtime 目前从 `dev-config/` 文件读取智能体、技能、MCP 配置。本任务在 control-plane 中建立这些资源的正式管理：智能体与版本、环境、技能、MCP 服务、租户与项目级权限策略、租户级设置，并通过内部接口与失效通知分发给 runtime。

完成后，管理员在 control-plane 中发布一个智能体版本，业务方就能用它创建会话，全程不需要改任何文件。

## 必读

1. 架构设计：「领域模型」（智能体、智能体版本、环境）、「数据架构」（control-plane 部分、跨服务数据流）、「运行时架构」中的「引擎装配」「权限判定」（五层规则来源）「上下文管理」（提示词分层）、「工作区与沙箱」中的环境规格、「API 设计」中的智能体公开接口。
2. `meridian-engine-spi` 中 `ResolvedAgentSpec`、`EnvironmentSpec`、`PermissionPolicy` 的定义与注释。
3. 已有模块 `CLAUDE.md`：`meridian-control-plane`、`meridian-runtime`。
4. runtime 中 `AgentConfigProvider`、`PolicyStorePort` 及其文件实现。

## 范围

### 要做

- control-plane 的数据模型与管理 API：`agents`、`agent_versions`、`environments`、`skills`、`skill_versions`、`mcp_servers`、租户与项目级权限策略、`tenant_settings`（租户级系统提示词、联网搜索后端）。
- 智能体版本：规格用 JSON Schema 校验，与 `ResolvedAgentSpec` 一一对应；发布后不可变；版本对比接口。
- 公开接口 `GET /v1/agents`、`GET /v1/agents/{id}`：写入 `meridian-v1.yaml`，API Key 可访问，只返回本项目已发布的版本。
- 内部配置接口：`/internal/v1/agent-versions/{id}:resolve`（返回完整解析后的配置，凭据只给引用）、`/internal/v1/environments/{id}`、项目级权限策略的读写、租户设置的读取。
- 失效通知：发布、修改、删除时在 Pub/Sub `config.invalidate` 发布。
- runtime 侧：基于 control-plane 的 `AgentConfigProvider`、`PolicyStorePort` 实现，带缓存与失效；文件实现只保留在测试与开发 profile。
- 技能包：上传 `SKILL.md` 及附带文件（存对象存储），版本化。

### 不做

- 预算（T16）、控制台页面（T22、T23）。
- 知识库的真实存在性校验（T18）。本任务经 `KnowledgeCatalogClient` 校验，先提供开发实现并留 `TODO(T18)`。

## 交付物

### 1. 智能体版本规格

- JSON Schema 文件放在 `meridian-api/src/main/resources/schema/agent-spec.schema.json`，字段与 `ResolvedAgentSpec` 一一对应；写一个测试证明二者一致（例如用 Schema 生成的示例反序列化为 `ResolvedAgentSpec` 再序列化，结果相同）。
- 发布时校验：模型引用存在、工具集合法、子智能体的工具集是父智能体的子集、知识库存在（经 `KnowledgeCatalogClient`）、MCP 服务存在、技能版本存在。

### 2. 环境

- 档位、规格（`small`、`medium`、`large`）、出网白名单；修改时递增 `revision`，保留修订历史。

### 3. 技能与 MCP 服务

- 技能包上传后校验 `SKILL.md` 的 frontmatter，附带文件存对象存储；用 T12a 的内置 docx 技能做上传与版本化的测试数据。
- MCP 服务：名称、传输类型、地址、认证方式（引用凭据）、每个工具的副作用等级标注；注册时探测一次服务并保存工具清单，供控制台选择。

### 4. 租户设置

- 租户级系统提示词：进入 T10b 提示词分层中的"租户"层。
- 联网搜索后端：类型、端点、凭据引用；替换 T10b 的文件配置。

## 实现要点

- runtime 的缓存失效要在收到通知后立即生效；同时保留一个较短的过期时间作为兜底（通知丢失时）。
- 解析接口返回的配置，必须让 runtime 不再需要额外查询就能装配引擎（除了凭据明文）。
- 引擎实例缓存的键包含 `environment_revision`，环境修改后新会话使用新配置，运行中的会话不受影响（与架构设计一致）。

## 验收标准

- [T15b-A01] 通过管理 API 创建供应商账号、模型、凭据、智能体、发布版本、环境后，业务方用 API Key 创建会话并完成一轮真实对话（`-Plive`，`qwen-plus`），全程不读 `dev-config/`。验证：端到端集成测试。
- [T15b-A02] JSON Schema 与 `ResolvedAgentSpec` 一致性测试通过；发布时的六项校验都有失败用例。验证：测试。
- [T15b-A03] 已发布的版本不可修改；版本对比接口返回差异。验证：集成测试。
- [T15b-A04] 配置失效：发布新版本、修改环境后，runtime 在通知后立即使用新配置创建新会话；运行中的会话继续使用原版本。验证：集成测试。
- [T15b-A05] 项目级权限策略与"记住此决定"的项目级规则存于 control-plane，并在 runtime 的权限合成中生效。验证：集成测试。
- [T15b-A06] MCP 服务注册时探测并保存工具清单；在智能体版本中引用后，会话中出现 `mcp__` 工具，凭据来自凭据库。验证：集成测试（进程内 MCP 服务）。
- [T15b-A07] `GET /v1/agents` 用 API Key 只能看到本项目已发布的智能体；租户级系统提示词出现在请求的租户层；联网搜索后端按租户设置切换。验证：集成测试。
- [T15b-A08] 技能包上传、版本化、在会话中被加载（docx 技能）。验证：集成测试。
- [T15b-A09] `scripts/dev/verify.sh` 通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 更新 `meridian-control-plane`、`meridian-runtime`、`meridian-api` 的 `CLAUDE.md`。
- 在 `PLAN.md` 的"登记事项"中记录 `dev-config/` 的剩余用途（只用于测试与本地开发）与 `TODO(T18)`。
