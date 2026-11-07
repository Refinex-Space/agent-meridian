# T09a 模型层：RoutedModel、限流、预算、计量与压缩守护

| 项 | 内容 |
|---|---|
| 阶段 | 1 M0 内核与运行时核心 |
| 依赖 | T08 |
| 推荐配置 | Opus 5.5，effort xhigh，新会话 |
| 规模 | 大 |
| 关联验证项 | P13 |

启动方式：在仓库根目录开新会话，输入 `/task T09a`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；AgentScope 只以 2.0.4 为准（读法见 `docs/plan/references.md`）。
- 只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

到目前为止，智能体只用 `ScriptedModel` 运行。本任务接入真实模型：`RoutedModel` 负责供应商解析、凭据、重试与降级、限流、预算、计量，所有模型调用（包括压缩器的内部调用）都经过它。同时实现压缩守护，并记录每次模型请求的上下文构成。

这是第一次用 `DASHSCOPE_API_KEY` 做真实测试。真实模型的输出不确定，测试只断言结构性条件；同时严格控制调用次数与 token。OpenAI 兼容的直通接口、Embedding 与 rerank 接口、脚本化模型服务在 T09b。

## 必读

1. 架构设计「运行时架构」：「引擎装配」（`model`、`fallbackModel`、`maxRetries`、`compaction` 行）、「平台中间件」（`CompactionGuardMiddleware` 与说明）、「上下文管理」「模型调用」；「计量与预算」。
2. `docs/engineering/testing.md`「真实模型测试」（端点、模型、成本规则都已实测）。
3. 已有模块 `CLAUDE.md`：`meridian-engine-agentscope`、`meridian-runtime`。
4. 实现时查阅（AgentScope 2.0.4 源码，按需定位，不通读）：`ChatModelBase`（`doStream`、`setContextWindowSize`）、OpenAI 兼容模型类与其构造方式、`ChatUsage`、`ModelCallEndEvent`、`ConversationCompactor`（异常处理与失败摘要文本）、`CompactionMiddleware`、`CompactionConfig`（`flushBeforeCompact`、`offloadBeforeCompact`）、`modelForCall` 的 fallback 逻辑。

## 范围

### 要做

- `engine.agentscope.model.RoutedModel` 与生产用的模型工厂（替换 T03 的测试工厂）。
- `ModelGateway` 端口在 runtime 中的实现：供应商账号与凭据解析、Redis 令牌桶限流、Redis 预算预扣与结算、用量记录（outbox 的 `usage` 主题）。
- `CompactionGuardMiddleware` 与压缩配置；压缩事件。
- 上下文构成摘要。
- 对话、流式、压缩的真实模型测试。
- M0 验证 P13 的定稿。
- 指标：`meridian_model_tokens_total`、`meridian_model_first_token_seconds`、`meridian_budget_rejections_total`。

### 不做

- OpenAI 兼容直通接口、Embedding 与 rerank 接口、`ScriptedOpenAiServer`（T09b）。
- control-plane 中的供应商、模型、价格表、预算配置（T15a、T16）。本任务通过 runtime 内部端口读取，提供文件实现：`dev-config/providers/*.yaml`，凭据只写环境变量名（例如 `DASHSCOPE_API_KEY`），由 `SecretResolver` 的开发实现从环境变量读取。

## 交付物

### 1. `RoutedModel`

按架构设计「模型调用」的四步实现，每次 `doStream` 从 Reactor Context 取 `PlatformContext`：

1. 经 `ModelGateway` 解析供应商账号与凭据；用 AgentScope 对应的模型类构造底层客户端，按"供应商账号加模型"缓存。本任务至少实现 OpenAI 兼容类型（DashScope 走 `compatible-mode/v1`），并为 Anthropic、Gemini 留出同一扩展点（实现与否以 2.0.4 的模型类可用性为准，不可用的写入报告）。
2. 限流：RPM 与 TPM 的 Redis 令牌桶（Lua 原子操作），等待最长 30 秒，超时抛 `RateLimitTimeoutException`。
3. 预算预扣："估算输入 token 加 `max_tokens`"，四级预算（租户、项目、API Key、会话）任一不足即抛 `BudgetExceededException`。
4. 调用底层模型；流结束时按 `ChatUsage` 结算预扣，写用量记录（输入、输出、缓存读、缓存写、推理分别记）。

错误处理：首包之前的供应商错误（连接失败、5xx、429）重试 2 次，仍失败且配置了备用模型则切换；预算与限流异常不重试、不切换；首包之后的错误不切换。装配时**不设置** AgentScope 的 `fallbackModel`，`maxRetries(0)`。

`setContextWindowSize` 按模型目录中的上下文窗口设置。

上下文构成：每次模型请求记录构成摘要，即系统提示词、历史消息、工具结果、技能清单、注入内容（steer、前置检索、恢复说明）各自的 token 估算与条数，以及缓存命中的 token。摘要放进 `span.model_request_start` 事件的数据中，供控制台展示"模型这一轮实际看到了什么"（架构设计「上下文管理」）。不记录内容正文。

### 2. 压缩守护

- `CompactionConfig`：`flushBeforeCompact(false)`、`offloadBeforeCompact(false)`，`reserved` 默认 20000 token，摘要提示词由平台维护（要求保留目标、关键决定、产物、未完成事项、下一步）。
- `CompactionGuardMiddleware` 分内外两层夹住 harness 的 `CompactionMiddleware`：外层在压缩前预检预算、限流、租约，不满足就抛平台异常；内层检查压缩结果，摘要以 `(Summarization failed` 开头时抛 `CompactionFailedException`；新出现 `__compaction_summary__` 消息时产生 `agent.context_compacted` 事件。
- 实际执行顺序由 `order()` 决定。测出真实顺序，确认两层确实夹住压缩中间件；做不到时按决策分级处理。

### 3. 真实模型测试（`*LiveIT`，`live` 标签）

- 对话：`qwen-plus` 通过 `HarnessAgent` 完成一次包含工具调用的完整轮次，断言产生了工具调用、工具名正确、最终有文本回答，用量被记录。
- 流式：首 token 时间被记录，`usage` 被结算。
- 压缩：设置很小的上下文窗口，触发一次真实压缩，摘要非空且不以失败文本开头。
- 遵守测试规范的成本约束；没有密钥时跳过。

## 实现要点

- 预扣与结算必须成对：用 `doFinally` 保证流被取消或出错时也会结算（退回未使用的部分）。
- 令牌桶与预算的 Lua 脚本各自有单元测试（用 Testcontainers 的 Redis）。
- 估算输入 token 用简单可靠的方法（例如按字符数估算并留余量），在注释中写明误差方向：宁可多扣、事后退回。
- 供应商的模拟用 WireMock 或 MockWebServer，覆盖首包前 5xx、首包后断流、429 与 `Retry-After`。

## 验收标准

- [T09a-A01] 首包前的 5xx 重试后切到备用模型；首包后错误不切换；预算与限流异常不触发重试与切换。验证：用模拟供应商的集成测试。
- [T09a-A02] 限流：超过 RPM 时请求等待；等待超过 30 秒抛 `RateLimitTimeoutException`，本轮以 `session.error`（`retry_status=retryable`）结束并重新排队。验证：集成测试。
- [T09a-A03] 预算：任一级预算不足时本轮以 `budget_exceeded` 结束；流被取消时预扣被退回；结算后 Redis 计数与用量记录一致。验证：集成测试。
- [T09a-A04] 压缩器的内部模型调用同样经过限流、预扣与计量。验证：集成测试（断言用量记录中包含压缩调用）。
- [T09a-A05] P13：两层守护确实夹住压缩中间件；预算耗尽时压缩被阻止；失败摘要被拦截，不替换历史。验证：测试；报告 `docs/poc/P13-中间件顺序.md`。
- [T09a-A06] 用量记录包含输入、输出、缓存读、缓存写、推理 token，并进入 outbox `usage` 主题。验证：集成测试。
- [T09a-A07] 本任务列出的指标已注册并有值；`span.model_request_start` 事件带上下文构成摘要，且不含内容正文。验证：测试。
- [T09a-A08] 真实模型：工具调用轮次、流式、压缩，全部通过。验证：`./mvnw -Plive ...` 的输出（命令与结果，不含密钥）。
- [T09a-A09] `scripts/dev/verify.sh` 通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 更新 `meridian-engine-agentscope`、`meridian-runtime` 的 `CLAUDE.md`：模型工厂、开发期供应商配置、真实模型测试的运行方式。
- 在根 `CLAUDE.md` 的命令一节写明真实模型测试的命令与前提。
- 更新 `docs/poc/README.md` 中 P13 的状态。
