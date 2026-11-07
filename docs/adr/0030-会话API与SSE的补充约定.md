# ADR-0030 会话 API 与 SSE 的补充约定

| 项 | 内容 |
|---|---|
| 状态 | 已采纳（补充） |
| 日期 | 2026-11-07 |
| 决策人 | T07 执行会话 |
| 影响的架构设计章节 | 「核心表」、「状态机」、「消息投递」、「认证」「授权」、「API 设计」中的「约定」「资源」「SSE 协议」（只补充，不改既有条款） |

## 背景

T07 落地公开 API 的会话部分与 SSE 事件流。架构设计规定了资源、事件信封、SSE 五步算法、幂等与认证的形态，没有规定以下细节，而它们决定对外契约（会被业务系统依赖、难以回退）或影响 T08、T14a、T17a、T27 的实现：

1. 删除与归档的语义：删除后能否查询、接口返回什么、执行中的轮次怎样处理；哪些状态可以归档。
2. 创建会话时 `agent` 字段取智能体 ID 还是版本 ID，环境修订号怎样确定。
3. 一次请求发送多个事件时的原子性与投递方式。
4. SSE 怎样"先订阅 Redis Stream 并缓冲"而不让每个连接独占 Redis 连接；线程流怎样判断跳号。
5. 幂等的请求指纹与作用域、重放的标识。
6. 开发期认证与没有 API Key 来源时的行为；`traceparent` 做到什么程度。

## 候选方案

**SSE 读取 Redis Stream**

- A：每个连接一个阻塞的 `XREAD BLOCK`。Spring Data Redis 为每次阻塞读取另开连接，连接数随 SSE 连接线性增长。
- B：每个连接各自轮询不阻塞的 `XREAD`。不占独占连接，但 Redis 请求数随连接数线性增长（1000 个连接、100 毫秒间隔即每秒 1 万次）。
- C：每个节点一个读取循环，用一条不阻塞的 `XREAD` 读取全部被订阅的会话，按会话分发给连接。请求数只与节点数有关，延迟上限是轮询间隔。

**删除接口的返回**

- A：204，删除后查询返回 404。T17a 要求删除是后台作业、"状态可查询"，204 加 404 无法表达清理进度。
- B：202 加会话体，会话进入 `terminated` 并带 `deleted_at`；清理完成前按 ID 仍可查询，之后 404。T17a 可以直接沿用。

## 决策

SSE 读取选 C，删除接口选 B。全部约定如下：

1. **删除与归档。** 删除返回 202 与会话（`status=terminated`、`deleted_at` 不为空），取消排队、执行中与等待回应的轮次（执行中的轮次释放租户并发名额，持有它的节点在下一次带租约的写入或续租时放弃本轮），未回应的待处理动作作废（`expired`），写 `session.status_terminated(deleted)`；重复删除返回同样的结果；删除的会话不出现在列表中，按 ID 仍可查询，数据在 24 小时内由 T17a 清理。归档只允许 `idle` 与 `requires_action`（「状态机」），排队与等待回应的轮次取消、动作作废，写 `session.status_terminated(archived)`；执行中（`running`、`rescheduled`）返回 409 `session.busy`；归档已归档的会话返回当前会话。两者都先取主线程的咨询锁，与发送消息、结束轮次的事务串行。新增列 `sessions.deleted_at`（迁移 `V0003`）。
2. **创建会话。** `agent` 可以是智能体 ID（`agt_…`，绑定当前发布的版本）或版本 ID（`agv_…`，固定版本）；`environment` 绑定当前修订号。`AgentConfigProvider` 增加 `currentAgentVersion`、`currentEnvironment`；找不到时为 404 `agent.not_found`、`environment.not_found`。T15b 起按项目隔离智能体与环境。
3. **发送事件。** 一批最多 20 个事件，在一个事务里按顺序校验与持久化，任一不合法整批回滚。主线程空闲时本批的全部消息合成一个 `messages` 轮；否则逐条进收件箱。跟在 `user.interrupt` 后面的消息按 queue 投递（「消息投递」）。回应事件必须对应会话中仍在等待、类型匹配的动作，否则 409 `event.action_not_found`、`event.action_type_mismatch`、`event.action_not_pending`；同一批里重复回应同一动作为 400。T07 只做到校验、持久化与转交收件箱：中断写 `interrupt_requested_at`、动作标为已回应与生成恢复轮由 T08 实现。事件 ID 由调用方先生成（`SessionEventWriter.append(batch, eventIds)`，登记事项 D10）。
4. **SSE。** 每个节点的 `SessionStreamHub` 每 100 毫秒（`meridian.runtime.sse.poll-interval`）用一条不阻塞的 `XREAD` 读取全部被订阅的会话。连接先登记再读取 Stream 当前最新条目的 ID 作为起点，起点之后的条目一定会被交付；Redis 不可用时登记照常完成，持久化事件由对账补发。连接按会话的全部序号记录"已处理到"的位置：Redis Stream 中是会话全部线程的事件，其他线程的条目照样推进位置，所以线程流与 `?threads=main` 不会把其他线程的序号当成缺失，回补只查询范围内的事件。推送 `session.status_terminated` 后关闭连接（线程流不推送它，同样关闭）；连接缓冲超过 1 万个条目时关闭，由客户端带 `Last-Event-ID` 重连。`?threads=main` 推送主线程、会话级事件（`thread_id` 为空）与 `session.thread_created`、`session.thread_status_*`。
5. **幂等。** 指纹是方法、路径与请求 DTO 规范化 JSON（映射按键排序）的 SHA-256，空白与字段顺序不同的请求体视为相同；作用域是 API Key（ADR-0020 的 `principal`）；重放的响应带 `Idempotent-Replayed: true`。业务以异常结束时键被释放，可以重试。
6. **认证与授权。** `meridian-security` 的 `ApiKeyResolver` 是可阻塞的端口，`ApiKeyWebFilter` 经 `Blocking` 调用它，认证 `/v1/` 下的全部路径并把 `PlatformContext`（租户、项目、API Key）写入 Reactor Context。`DevApiKeyResolver` 由 `meridian.security.dev-api-keys.enabled` 启用，只允许 `dev`、`test` profile，其他 profile 下启用则启动失败；没有任何来源时一律拒绝（`auth.invalid_api_key`），不会放行。作用域在处理器调用用例之前检查。错误码：`auth.unauthenticated`、`auth.invalid_api_key`（401），`auth.insufficient_scope`（403）。
7. **`traceparent`。** 合法的 W3C `traceparent` 在响应中原样返回，trace-id 写入日志 MDC 的 `trace_id`；不合法的值被忽略。跨服务传播与 span 由 T27 接入 OpenTelemetry 后完成。
8. **请求体严格解析。** runtime 的请求体遇到未声明的字段返回 400 `request.invalid`（`spring.jackson.deserialization.fail-on-unknown-properties`）；参数约束校验失败返回 400 `request.validation_failed`，字段名为参数在 HTTP 中的名字（`ProblemMapper` 映射 `HandlerMethodValidationException`）。

## 影响

- 架构设计：「核心表」的 `sessions` 增加 `deleted_at`；「SSE 协议」补充第 1 步的落地方式与线程流的位置判断；「资源」补充删除与归档的返回；版本升至 v1.6。
- 代码：迁移 `V0003`；`AgentConfigProvider` 两个方法；`TurnTransitions.lockActive`、`cancelActive`；`PendingActionRepository` 的查询与作废；`ReactiveRedisMessaging.lastRecordId`、`read`；`meridian-security` 的 `apikey` 包与 `TraceParentWebFilter`。
- T08：在 `EventIntake` 的 `TODO(T08)` 处实现中断与回应的语义。T14a：提供按哈希查找的 `ApiKeyResolver`，关闭开发期 Key。T15b：`AgentConfigProvider` 按项目隔离。T17a：删除的清理作业扫描 `deleted_at`。T27：`traceparent` 传播到轮次与跨服务调用。
- 风险：SSE 的实时延迟上限是轮询间隔（100 毫秒），单节点的 Redis 读取压力与延迟由 R1 的容量验证（P7）核对；Hibernate Validator 第一次校验失败时在事件循环上读取消息资源包（每个进程一次）。
