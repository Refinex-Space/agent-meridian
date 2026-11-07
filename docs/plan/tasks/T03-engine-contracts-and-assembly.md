# T03 内核契约、ScriptedModel 与 HarnessAgent 装配

| 项 | 内容 |
|---|---|
| 阶段 | 1 M0 内核与运行时核心 |
| 依赖 | T02 |
| 推荐配置 | Opus 5.5，effort xhigh，新会话 |
| 规模 | 大 |
| 关联验证项 | P1 |

启动方式：在仓库根目录开新会话，输入 `/task T03`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；AgentScope 只以 2.0.4 为准（读法见 `docs/plan/references.md`）。
- 只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

架构原则第 2 条：框架类型不出适配层。本任务定义平台与内核之间的全部契约（`meridian-engine-spi`），实现 `HarnessAgent` 的装配（`meridian-engine-agentscope` 的 `assembly` 包），并提供后续所有智能体测试都依赖的 `ScriptedModel`。

这是整个平台最关键的边界。契约设计不好，后面每个任务都会在适配层里打补丁。所以本任务只做契约、装配和测试夹具，执行一轮的逻辑留给 T04。

## 必读

1. 架构设计「运行时架构」全节，重点：「内核适配层」「端口」「身份映射」「引擎装配」「平台中间件」「平台工具」「状态机」「事件映射」「待处理动作」「子智能体」。
2. 架构设计「代码仓库与模块」中的「端口」与「包结构」。
3. `docs/plan/references.md`「读 AgentScope 2.0.4」：先执行 `./mvnw dependency:sources -pl meridian-engine-agentscope`，后续一律读 2.0.4 的 sources jar。
4. 实现时查阅（AgentScope 2.0.4 源码，按需定位，不通读）：`HarnessAgent` 与其 `Builder`、`ReActAgent` 的 `Builder`、`Model`、`ChatModelBase`、`ChatResponse`、`ChatUsage`、`RuntimeContext`、`AgentStateStore`、`MiddlewareBase`、`AgentEventType`、`GenerateReason`、`PermissionContextState`。

## 范围

### 要做

- `meridian-engine-spi` 的全部类型。
- `meridian-engine-agentscope` 的 `AgentAssembler` 与缓存。
- `meridian-engine-agentscope-test` 的 `ScriptedModel` 与内存端口夹具。
- M0 验证项 P1 与报告。

### 不做

- `AgentScopeEngine.runTurn` 的实现、事件映射、`FencedAgentStateStore`（T04）。
- `RoutedModel`（T09a）。装配时通过一个模型工厂接口获得 `Model`，本任务只提供测试实现。
- 平台中间件和平台工具的实现（T04 起）。装配表中涉及它们的位置，本任务留出注入点。

## 交付物

### 1. `meridian-engine-spi`

所有类型都是平台自己的，不出现任何 AgentScope 类型。用 `record` 与 `sealed interface` 表达，每个类型写中文 Javadoc 并注明对应的架构设计章节。

| 包 | 类型 |
|---|---|
| `engine` | `AgentEngine`（`runTurn`、`interrupt`、`shutdown`，签名按架构设计）；`TurnContext`（`PlatformContext`、线程与轮次信息、合成后的权限策略、每个子智能体预先合成的权限策略 `subagentPolicies`、自定义工具声明、恢复说明的来源、`agent_version_id` 与 `environment_id`、`environment_revision`）；`ThreadRef` |
| `engine.input` | `TurnInput` 及四种输入：`NewMessages`、`Confirmations`、`CustomToolResults`、`RecoveryOnly`；`InboundMessage`（文本、图片、文件引用等内容块）、`ToolConfirmation`、`CustomToolResult`、`RecoveryNote` |
| `event` | `EngineEvent` 的 `sealed` 层级，与架构设计「事件」一节的事件类型一一对应，包括仅在流上出现的增量事件；`StopReason` 枚举（`end_turn`、`interrupted`、`max_turns`、`budget_exceeded`、`tools_denied`、`error`）；待处理动作的描述类型 |
| `spec` | `ResolvedAgentSpec`：运行时需要的、已解析的智能体版本配置（模型引用、系统提示词、工具集、MCP 服务、技能、子智能体、权限策略、压缩配置、计划模式、知识库绑定与前置检索、`maxIters`）；`EnvironmentSpec`（档位、规格、出网白名单）；`PermissionPolicy`（平台的规则模型：规则、模式） |
| `port` | 架构设计「端口」表中的全部端口接口 |
| `driver` | `SandboxDriver`、`ModelInvoker`，及其参数与返回类型（`SandboxHandle` 等） |

`ResolvedAgentSpec` 将被 control-plane 的智能体版本 JSON 映射而来（T15b），字段命名与含义写清楚，单位和取值范围写进注释。

### 2. `meridian-engine-agentscope` 的 `assembly` 包

- `AgentAssembler`：按架构设计「引擎装配」表，把 `ResolvedAgentSpec` 加 `EnvironmentSpec` 装配为 `HarnessAgent`。表中每一行都要落实；涉及尚未实现的组件（`RoutedModel`、平台中间件、平台工具、`FencedAgentStateStore`、文件系统、技能仓库），通过构造参数注入的工厂或提供者获得，本任务提供测试替身。
- 实例缓存：键为 `(agent_version_id, environment_id, environment_revision)`，容量 500，空闲 30 分钟可淘汰；条目带引用计数，归零才 `close()`。提供 `acquire` 与 `release` 两个入口，`release` 在 `finally` 中调用。
- 模型工厂接口（引擎内部）：根据模型引用返回 `Model` 实例。生产实现由 T09a 提供。

### 3. `meridian-engine-agentscope-test`

- `ScriptedModel`：实现 AgentScope 的 `Model`。支持按脚本依次返回：文本（可分块流式）、思考块、单个或多个工具调用、结构化输出、首包前错误、首包后错误、带 `ChatUsage` 的结束。记录每次收到的请求（消息、工具 schema、生成参数），供断言。提供简洁的构建 DSL。
- 内存端口夹具：本任务及 T04 需要的端口的内存实现，放在测试夹具包中，供后续场景测试复用。

### 4. M0 验证 P1

- 证明 AgentScope 2.0.4 的 core 与 harness 在 JDK 25、Spring Boot 4.1.2 下运行，Jackson 2 与 Jackson 3 共存。
- 做法：在 `meridian-runtime` 的 `config` 包下写一个 Spring Boot 集成测试，启动最小的 WebFlux 应用上下文（平台 API 用 Jackson 3），在同一个 JVM 中用 `AgentAssembler` 装配一个 `HarnessAgent`，用 `ScriptedModel` 完成一次包含工具调用的 `streamEvents` 调用。
- 报告写到 `docs/poc/P01-运行基线.md`，包括环境、做法、依赖树关键行、结论；更新 `docs/poc/README.md` 的状态。

## 实现要点

- **先读源码，再定契约**：`ResolvedAgentSpec` 与 `TurnInput` 的字段，要能无损地翻译成 AgentScope 需要的东西。逐项确认装配表中提到的每个 Builder 方法在 2.0.4 中存在、签名与语义一致；不一致的按决策分级处理。
- **装配的可测试性**：`AgentAssemblerTest` 要能逐项断言装配表的每一行。能从构建结果读出的（例如 Toolkit 中有哪些工具、没有哪些内置工具）就直接断言；读不出的，通过注入替身断言它被调用、参数正确。
- **缓存的并发正确性**：并发 `acquire` 同一个键只装配一次；淘汰与 `acquire` 并发时，不会关闭一个正在被使用的实例。用并发测试证明。
- 契约类型要足够表达 T04 到 T13 的需求，但不要为 M2、M3 的能力预留字段。

## 验收标准

- [T03-A01] `meridian-engine-spi` 不依赖任何 AgentScope 与 Spring 类型。验证：架构测试与依赖树。
- [T03-A02] `EngineEvent` 层级覆盖架构设计「事件」一节的全部事件类型。验证：一个列出全部类型的测试，与文档清单逐一对应。
- [T03-A03] 装配表的每一行都有对应断言，`AgentAssemblerTest` 全部通过。验证：测试报告中的用例名与装配表的对照。
- [T03-A04] 实例缓存：同键并发 `acquire` 只装配一次；引用计数未归零时淘汰不会关闭实例；归零后关闭。验证：并发测试。
- [T03-A05] `ScriptedModel` 能驱动 `HarnessAgent` 完成：纯文本回答、单工具调用、并行工具调用、首包前错误、首包后错误，并能断言收到的请求。验证：场景测试。
- [T03-A06] P1：Spring Boot 4.1.2、JDK 25 下，同一 JVM 中 Jackson 3 的 Web 序列化与 AgentScope 的智能体调用都正常。验证：集成测试通过，报告 `docs/poc/P01-运行基线.md`。
- [T03-A07] 所有公开类型有中文 Javadoc 并注明架构设计章节。验证：Checkstyle 通过，抽查说明。
- [T03-A08] `scripts/dev/verify.sh` 通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 为 `meridian-engine-spi`、`meridian-engine-agentscope`、`meridian-engine-agentscope-test` 创建模块 `CLAUDE.md`。`meridian-engine-agentscope` 的"易错点"写入本任务核实的 AgentScope 2.0.4 行为差异。
- 发现与架构设计不符的 AgentScope 行为，按决策分级处理，并写进 P1 报告。
