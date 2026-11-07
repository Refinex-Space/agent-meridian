# T09b 模型直通接口、Embedding 与 rerank、脚本化模型服务

| 项 | 内容 |
|---|---|
| 阶段 | 1 M0 内核与运行时核心 |
| 依赖 | T09a |
| 推荐配置 | Opus 5.5，effort high，新会话 |
| 规模 | 中 |
| 关联验证项 | 无 |

启动方式：在仓库根目录开新会话，输入 `/task T09b`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；AgentScope 只以 2.0.4 为准（读法见 `docs/plan/references.md`）。
- 只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

T09a 让智能体经 `RoutedModel` 调用真实模型。本任务做三件事：

1. 把同一条受控的模型通道开放给业务方（OpenAI 兼容的直通接口）。
2. 给知识库提供内部的 Embedding 与 rerank 接口。
3. 提供一个可以独立运行的脚本化模型服务 `ScriptedOpenAiServer`。

第三件决定了后面所有端到端测试能否稳定。`ScriptedModel` 只存在于测试 classpath，起着的 runtime 只能接真实模型；审批触发、steer 与中断的时序、子智能体、上万条事件的会话，用真实模型都无法稳定复现。`ScriptedOpenAiServer` 是一个 OpenAI 兼容的 HTTP 服务，runtime 把它当成普通供应商来调用，控制台的 Playwright 用例、R2 的场景预演、M1 验收都默认用它，只有 `live` 用例才走 DashScope。

## 必读

1. 架构设计「API 设计」中的模型直通端点、「模型调用」、原则第 10 条（测试不依赖真实模型）。
2. `docs/engineering/testing.md`「真实模型测试」与「前端测试」。
3. 已有模块 `CLAUDE.md`：`meridian-runtime`、`meridian-test-support`、`meridian-api`。

## 范围

### 要做

- `ModelInvoker` 与 OpenAI 兼容接口：`POST /v1/chat/completions`（流式与非流式）、`POST /v1/embeddings`；内部接口 `POST /internal/v1/embeddings`、`POST /internal/v1/rerank`。
- `ScriptedOpenAiServer` 与开发期的 `scripted` 供应商配置。
- Embedding 与 rerank 的真实模型测试。

### 不做

- `/v1/responses`（T17b）。
- 服务 JWT 的正式实现（T14b）。本任务的内部接口用开发期的静态服务凭据，只在 `dev`、`test` profile 生效。

## 交付物

### 1. 直通与内部接口

- `/v1/chat/completions`：OpenAI 兼容的请求与响应（含 SSE 流式与 `usage`），经 `ModelInvoker` 调用 `RoutedModel`，同样限流、预扣、计量；作用域 `models:invoke`。
- `/v1/embeddings` 与 `/internal/v1/embeddings`：平台自己的 OpenAI 兼容 HTTP 客户端（不用 AgentScope 的 RAG 组件），批量、连接复用，同样计量。
- `/internal/v1/rerank`：DashScope 的 `compatible-api/v1/reranks` 端点。
- 以上接口写入 `meridian-v1.yaml`（公开部分），内部接口另写 `meridian-internal-v1.yaml`。

### 2. `ScriptedOpenAiServer`

放在 `meridian-test-support`，同时支持在测试中内嵌启动和独立启动（`scripts/dev/scripted-model.sh`，默认端口写进脚本与模块 `CLAUDE.md`）。

- 协议：OpenAI 兼容的 `/v1/chat/completions`（流式与非流式）、`/v1/embeddings`（按文本哈希生成确定的向量）、DashScope 格式的 rerank（按关键词重叠打分）。
- 脚本选择：取请求中最近一条以 `#script:<名称>` 开头的用户消息，使用该脚本；没有标记时使用默认脚本（简短回显）。服务本身无状态：当前是第几步，由该消息之后的 assistant 消息数决定。
- 脚本是响应序列，YAML 格式，放在模块资源目录。每一步可以是文本、`tool_calls`、流式逐块延迟、HTTP 错误码（含首包后断流）、`usage`。
- 提供覆盖后续任务所需情形的内置脚本：普通对话、调用一次工具、触发审批（调用 `ask` 级别的工具）、调用自定义工具、长时间流式（便于测 steer 与中断）、拉起子智能体、首包前 5xx。脚本清单与写法写进模块 `CLAUDE.md`。
- `dev-config/providers/scripted.yaml` 把它注册为一个 OpenAI 兼容的供应商，只在 `dev`、`test` profile 加载。

### 3. 真实模型测试（`*LiveIT`，`live` 标签）

- Embedding：`qwen3.7-text-embedding` 返回 1024 维。
- rerank：`qwen3.7-text-rerank` 对两段文本给出正确的相对顺序。
- `/v1/chat/completions` 经 DashScope 的一次流式调用，`usage` 被结算。
- 遵守测试规范的成本约束；没有密钥时跳过。

## 实现要点

- 直通接口与智能体的模型调用共用 `RoutedModel` 的限流、预扣、计量路径，不另写一套。
- `ScriptedOpenAiServer` 的响应格式以 OpenAI 官方格式为准，用 runtime 的真实 OpenAI 兼容客户端做契约测试，确保它对 runtime 而言与真实供应商没有区别。
- 脚本服务不读取任何密钥，也不对外网发请求。

## 验收标准

- [T09b-A01] `/v1/chat/completions` 的流式与非流式响应符合 OpenAI 格式，带 `usage`，并经过限流、预扣与计量。验证：契约测试与集成测试。
- [T09b-A02] `/v1/embeddings`、`/internal/v1/embeddings`、`/internal/v1/rerank` 可用并被计量；内部接口拒绝没有服务凭据的请求。验证：集成测试。
- [T09b-A03] 经 HTTP 启动的 runtime 以 `scripted` 供应商跑通一轮带工具调用的会话，事件序列与脚本一致；同一脚本重复运行结果相同。验证：集成测试。
- [T09b-A04] 内置脚本覆盖审批、自定义工具、长时间流式、子智能体、首包前 5xx 五种情形，各有一个用例证明可用。验证：集成测试。
- [T09b-A05] 真实模型：Embedding（1024 维）、rerank 顺序、直通流式调用，全部通过。验证：`./mvnw -Plive ...` 的输出（命令与结果，不含密钥）。
- [T09b-A06] `scripts/dev/verify.sh` 通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 更新 `meridian-test-support` 的 `CLAUDE.md`：`ScriptedOpenAiServer` 的启动方式、脚本格式、内置脚本清单。
- 在根 `CLAUDE.md` 的命令一节写明脚本化模型服务的启动命令。
