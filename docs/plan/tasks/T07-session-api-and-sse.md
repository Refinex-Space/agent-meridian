# T07 会话 API 与 SSE

| 项 | 内容 |
|---|---|
| 阶段 | 1 M0 内核与运行时核心 |
| 依赖 | T06 |
| 推荐配置 | Opus 5.5，effort high，新会话 |
| 规模 | 大 |
| 关联验证项 | 无 |

启动方式：在仓库根目录开新会话，输入 `/task T07`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；AgentScope 只以 2.0.4 为准（读法见 `docs/plan/references.md`）。
- 只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

业务系统接入平台的唯一方式是公开 API。本任务按契约优先的原则，写出公开 API 规范 `meridian-v1.yaml` 的会话部分，生成代码，实现会话、事件、线程、待处理动作的接口与 SSE 事件流。

SSE 是业务方体验平台的第一入口：断线、重连、乱序、丢事件都会被直接感知。所以 SSE 严格按架构设计的五步算法实现，并用测试证明"事件不丢、不重、按序"。

## 必读

1. 架构设计「API 设计」全节、「安全架构」中的「认证」「授权」、「运行时架构」中的「消息投递」「待处理动作」（只看 API 层面的形态）。
2. `docs/engineering/coding-standards.md`「API」。
3. 已有模块 `CLAUDE.md`：`meridian-runtime`、`meridian-security`、`meridian-common`。

## 范围

### 要做

- OpenAPI 规范 `meridian-api/src/main/resources/openapi/meridian-v1.yaml`：会话、事件、线程、待处理动作、SSE 流的完整定义；全部事件类型的 schema。
- 代码生成：`meridian-api` 只生成 DTO；`meridian-runtime` 生成 reactive 服务端接口。
- 接口实现：创建、查询、列表、更新、删除、归档会话；发送事件、列出事件；列出线程；列出待处理动作；会话流与线程流。
- 幂等（`Idempotency-Key`）、游标分页、problem+json、`X-Request-Id`、`traceparent` 透传。
- 开发期 API Key 认证（见下文）。
- 契约测试。

### 不做

- 发送事件后的业务语义（steer、中断、审批回应、自定义工具回传）只做到"事件被正确接收、持久化并转交收件箱"，具体语义在 T08。
- 真实的 API Key 与 OIDC 认证、限流（T14a、T14b）。
- 文件、工作区、模型直通等其他资源（各自的任务）。

## 交付物

### 0. 生成链验证（先做）

用一份包含判别器 `oneOf`、可空字段、`additionalProperties` 的最小 OpenAPI 3.1 规范，确认：openapi-generator 能生成 Jackson 3 的 DTO、Spring Boot 4 的 reactive 服务端接口与 servlet 服务端接口，三者都能编译；openapi-diff 能比较两个 3.1 规范。不支持的 3.1 特性写成规范的写作约束（放在 `meridian-api` 的 `CLAUDE.md`），记为补充型 ADR。验证通过后再写正式规范。

### 1. 规范

- 资源与端点按架构设计「资源」表中本任务范围内的行。
- 事件信封与全部事件类型的 schema 按架构设计「事件」一节；`user.*` 事件作为请求体，`agent.*`、`session.*`、`span.*` 作为响应与流的内容。
- 字段说明、错误码、示例用中文写在规范中；字段名 snake_case。
- 错误响应统一引用 problem+json 组件。
- 会话创建请求包含：`agent`、`environment`、`end_user_id`、`end_user_attributes`（含 `acl_tags`）、`interaction`、`metadata`、`custom_tools`（自定义工具声明，名称规则在 T08 校验）。

### 2. 生成与实现

- `api` 包中的处理器只做 decode → call → map；业务逻辑在 `app` 层。
- 会话创建时，通过 `AgentConfigProvider` 校验智能体版本与环境存在，并在 `sessions` 中固化 `agent_version_id`、`environment_revision`。
- 发送事件：校验事件类型与当前会话状态的组合是否合法（例如 `user.tool_confirmation` 只能在有对应待处理动作时发送）；持久化为 `session_events`，写收件箱，必要时创建轮次。
- 幂等：使用 `persistence.idempotency` 的通用实现（T02），在 `rt` 中建幂等表（新增迁移）；24 小时内同键同请求体返回首次结果，同键不同请求体返回 409。

### 3. SSE

- 算法严格按架构设计「SSE 协议」五步：先订阅 Redis Stream 并缓冲，再从数据库补发 `Last-Event-ID` 之后到当前 `last_seq` 的事件，按 `seq` 去重后转实时，跳号时从数据库回补，每 5 秒用 `last_seq` 对账。
- `ping` 每 15 秒；`?event_deltas=true` 才推送增量事件；单连接最长 60 分钟。
- 会话流与线程流的 `seq` 语义按架构设计「SSE 协议」：会话流 `seq` 连续；线程流与 `?threads=main` 只含部分事件，`seq` 不连续，服务端对账时只查询本线程在 `(上次 seq, last_seq]` 区间内的事件，其他线程占用的序号不算缺失。
- 指标：`meridian_sse_connections`、`meridian_sse_gap_refills_total`。

### 4. 开发期认证

- `meridian-security` 中定义 `ApiKeyAuthenticator` 与 `ApiKeyResolver` 端口：从 `Authorization: Bearer` 解析 API Key，得到主体（租户、项目、作用域），填充 `PlatformContext`。
- 本任务提供 `DevApiKeyResolver`：从配置读取开发用的 Key 与主体，只在 `dev` 与 `test` profile 生效。启动时若在其他 profile 下发现它被启用，应用启动失败。
- 作用域检查：`sessions:write`、`sessions:read` 按架构设计「授权」执行。

### 5. 契约测试

- 运行中的服务对每个端点的响应都符合规范（用 OpenAPI 校验库在测试中校验请求与响应）。
- 把 `openapi-diff` 接入构建：与上一个已提交版本比较，出现破坏性变更时构建失败（首个版本没有基线时跳过并说明）。

## 实现要点

- 先写规范并评审一遍：对照架构设计逐个检查资源、字段、事件类型、错误码，确认无遗漏，再生成代码。
- SSE 的正确性测试要制造真实的困难条件：Worker 在 XADD 前被取消、客户端在事件高峰时断开、带 `Last-Event-ID` 重连、Redis 短暂不可用。
- `end_user_id` 的信任边界按架构设计：按"项目 + `end_user_id`"隔离，跨项目不可见。

## 验收标准

- [T07-A01] `meridian-v1.yaml` 覆盖架构设计中本任务范围内的全部端点与事件类型，通过 OpenAPI 3.1 校验。验证：校验命令输出，加一份端点与事件类型对照清单。
- [T07-A02] 端到端：用 HTTP 创建会话、发送 `user.message`、订阅 SSE，收到 `session.status_running` 到 `session.status_idle` 的完整事件序列（`ScriptedModel`）。验证：集成测试。
- [T07-A03] SSE 不丢不重：在 Worker 于 XADD 前被取消、客户端断线重连、Redis 短暂中断三种情况下，客户端最终收到的 `seq` 连续、无重复。验证：集成测试。
- [T07-A04] `Last-Event-ID` 重连只补发之后的事件；`event_deltas=true` 时才有增量事件；`ping` 按时到达。验证：集成测试。
- [T07-A05] 幂等：同键同体返回首次结果，同键异体返回 409。验证：集成测试。
- [T07-A06] 跨项目隔离：项目 A 的 Key 读不到、写不到项目 B 的会话，即使 `end_user_id` 相同。验证：集成测试。
- [T07-A07] 错误响应为 problem+json，带 `code` 与 `request_id`；非法的事件与状态组合返回明确的错误码。验证：集成测试。
- [T07-A08] `DevApiKeyResolver` 在非开发 profile 下导致启动失败。验证：测试。
- [T07-A09] 契约测试覆盖全部端点；`openapi-diff` 已接入构建。验证：测试报告与构建配置。
- [T07-A10] 线程流与 `?threads=main` 订阅在其他线程持续产生事件时，不误判缺失、不重复补发。验证：集成测试。
- [T07-A11] 生成链验证完成：3.1 规范的 DTO、reactive 接口、servlet 接口都能编译，openapi-diff 可用；写作约束已记录。验证：验证工程的构建输出与约束文档。
- [T07-A12] `scripts/dev/verify.sh` 通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 创建 `meridian-api` 的模块 `CLAUDE.md`：规范位置、生成命令、改规范的流程。
- 更新 `meridian-runtime`、`meridian-security` 的 `CLAUDE.md`。
