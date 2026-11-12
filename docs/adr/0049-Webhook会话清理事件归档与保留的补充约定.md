# ADR-0049 Webhook、会话清理、事件归档与保留的补充约定

| 项 | 内容 |
|---|---|
| 状态 | 已采纳（补充） |
| 日期 | 2026-11-12 |
| 决策人 | T17a |
| 影响的架构设计章节 | 数据架构（核心表、跨服务数据流、数据保留）、安全架构（授权）、API 设计（资源、Webhook）、工作区与沙箱（会话沙箱） |

## 背景

T17a 补齐 runtime 中与数据生命周期有关的部分。架构设计规定了 Webhook 的四种事件、签名头格式、"24 小时内最多 8 次"的重试与至少一次语义（「Webhook」），删除会话在 24 小时内级联清理并写审计、会话 90 天无活动后事件归档到对象存储、事件默认保留 365 天且租户可配置（「数据保留」），`cp.webhooks` 只有表名。以下几处没有写到：

- **Webhook 配置在哪、投递在哪。** 配置是管理资源（control-plane），投递要在状态事件写入后进行（runtime）；投递记录的查询与手动重投由谁提供，签名密钥怎样保存与下发。
- **投递与事件写入的关系。** 事件写入在带租约的事务里，不能在其中调用 control-plane 读订阅，也不能等 HTTP。
- **子线程的 `session.error`（登记事项 D42）。** 子线程以 `retry_status=terminal` 结束时写 `session.error`，信封 `thread_id` 是子线程，类型与主线程的相同。
- **删除的完成与 Webhook。** 删除时写入的 `session.status_terminated(deleted)` 要投递，清理作业若先删掉会话数据，投递记录也随之消失。
- **删除清理的残留（D30、D35、D37）。** 工作区按命名空间前缀与对象前缀清除；进程在认领沙箱之后、提交之前崩溃时，带会话标签的 `SandboxClaim` 没有记进会话；清理期间仍在执行的轮次可能再认领沙箱。
- **归档的格式与读取。** 归档后会话还能继续使用（归档的是事件，不是会话），新事件写回数据库；读取要跨归档文件与数据库保持完整、按序，并与归档作业的并发提交一致。
- **保留期怎样下发、怎样执行。** `cp.tenants.event_retention_days` 已存在（T14a），runtime 没有读取它；按事件删除会在会话中留下前缀缺口，归档文件中的事件也要按期删除。

## 候选方案

**投递记录的查询与重投**

- A：管理 API（control-plane）提供，转发到 runtime 的内部接口。要新增 control-plane → runtime 的服务调用方向与令牌签发给自己的路径。
- B（采纳）：公开 API（runtime）提供 `/v1/webhook-deliveries`。控制台读 runtime 的数据本来就经公开 API 加 `Meridian-Project`（会话回放，ADR-0044）；业务系统作为接收方也能查询与重投。配置仍是管理资源。

**事件与投递解耦**

- A：事件写入事务里按订阅直接生成投递记录。需要在事务内读 control-plane 的订阅。
- B（采纳）：事件写入事务里只写一张 Webhook outbox（四种事件，事件信封的原文）；独立的投递器先按项目的订阅扇出为投递记录，再按到期时间投递。没有订阅的项目只多一行插入与一次删除。

**签名密钥的保存**

- A：作为项目级凭据存进凭据库，Webhook 引用凭据 ID。凭据会出现在凭据列表里，可以被单独删除或误用。
- B（采纳）：密文存在 `cp.webhooks`，用凭据库同一把租户数据密钥与 `SecretCipher` 加密（附加认证数据 `meridian:webhook:{tenant}:{webhook}`），明文只在创建响应与内部接口中出现。

**删除与 Webhook 的先后**

- A：清理不等投递。删除通知多半投递不出去。
- B（采纳）：清理作业等该会话的 Webhook outbox 与待投递记录处理完，最长等到删除后 12 小时（重试计划总长约 10.7 小时），之后作废未完成的投递并照常清理；仍在 24 小时内完成。

**归档的读取**

- A：归档后会话只读，不再写事件。与"归档的是 90 天无活动会话的事件"不符：调用方还可以继续发消息。
- B（采纳）：会话记录归档覆盖到的最大 seq（`events_archived_seq`），seq 不超过它的事件在归档文件，之后的在数据库；再次归档时合并旧文件与数据库中的新事件，写新文件后删除旧文件。读取在一个可重复读的只读事务中读指针与数据库，归档文件按键缓存。

## 决策

1. **Webhook 配置（control-plane）。** `cp.webhooks` 是项目级资源（`whk_`）：`url`、订阅的事件（四种的非空子集）、`description`、`enabled`、签名密钥密文。管理 API `/admin/v1/tenants/{t}/projects/{p}/webhooks` 增删改查，权限同 API Key（`API_KEY_READ`、`API_KEY_WRITE`：两者都是业务系统的接入配置）；创建时生成签名密钥（`whsec_` 加 32 字节随机数的 base64url），只在创建响应中出现一次。地址只允许 `http`、`https`，按 `EndpointPolicy` 校验：默认不放行任何内网与回环地址，`meridian.control-plane.webhooks.private-networks`（CIDR）与 `allow-loopback` 放行企业内网的接收方。变更写审计 `webhook.*` 并在提交后发布 `config.invalidate`（`type=webhook`）。内部接口 `GET /internal/v1/projects/{project_id}/webhooks` 只接受 runtime，返回该项目全部 Webhook 与签名密钥明文（`no-store`）。
2. **订阅事件的记录。** `SessionEventWriter` 在插入事件的同一事务里，对 `session.status_idle`、`session.status_requires_action`、`session.error`、`session.status_terminated` 写 `rt.webhook_events`（事件信封原文，与 `GET /v1/sessions/{id}/events` 的一项相同）。只推送主线程与会话级的事件：子线程的 `session.error` 不推送（D42）——子线程的失败已经作为 `spawn_subagent` 的工具结果交给主线程，会话是否出错以主线程为准。
3. **投递器。** `webhook.app.WebhookDispatcher` 是每个节点上的定期任务（默认 2 秒），两步都以 `FOR UPDATE SKIP LOCKED` 认领，多节点不重复：
   - 扇出：读 outbox，按项目取订阅（`WebhookSubscriptionPort`，经内部接口，缓存 60 秒，`config.invalidate` 的 `webhook` 淘汰该租户）；为每个启用并订阅该类型的 Webhook 插入 `rt.webhook_deliveries`（`whd_`，截止时间为创建后 24 小时），删除 outbox 行。读不到订阅时保留 outbox 行，下次重试。
   - 投递：认领到期的待投递记录（认领 60 秒），在事务外以 `POST` 发出信封原文，`Content-Type: application/json`，头 `Meridian-Signature: t={unix 秒},v1={hex(HMAC-SHA256(密钥, "{t}.{请求体}"))}`、`Meridian-Webhook-Id`、`Meridian-Delivery-Id`、`Meridian-Event-Type`；10 秒超时，2xx 为成功，不跟随重定向。每次投递按当前配置取地址与密钥：Webhook 已删除或停用时以 `webhook_unavailable` 失败。
   - 重试：失败后依次等待 30 秒、2 分钟、10 分钟、30 分钟、1 小时、3 小时、6 小时，总计 8 次，过了截止时间不再重试，记录置为 `failed`。至少一次：成功的结果写库之前进程崩溃，认领过期后会再投一次；接收方按信封的事件 `id` 去重。
   - 地址：连接时按 runtime 的 `meridian.runtime.webhook.private-networks`、`allow-loopback` 再校验解析结果（`GuardedResolverGroup`），防 DNS 重绑定；不走代理。
   - 投递记录保留 30 天（`delivery-retention`），由投递器顺带删除。
4. **投递记录的公开接口。** `GET /v1/webhook-deliveries`（按 `webhook_id`、`session_id`、`status` 过滤，游标分页，`sessions:read`）、`GET /v1/webhook-deliveries/{id}`、`POST /v1/webhook-deliveries/{id}:redeliver`（`sessions:write`，返回 202 与一条新的待投递记录，`redelivery_of` 指向原记录，事件 ID 与请求体不变）。只能看到与重投本项目的记录。
5. **会话清理。** `retention.app.SessionPurger` 是定期任务（默认 1 分钟），扫描 `deleted_at` 不为空的会话（`SKIP LOCKED` 锁行判定，单个会话失败不影响其他，下次重试）。一个会话依次：删除沙箱（`SandboxService.deleteSessionSandbox`：沙箱、快照、产物与清单、撤销出网授权），按会话标签删除残留的 `SandboxClaim`（`SandboxDriver.deleteSessionSandboxes`，D37 中已删除会话的部分），删除对象前缀 `rt/{tenant}/{session}/`（大结果、工作区大文件、归档事件）；最后在一个事务里删除工作区条目（`(tenant_id, project_id, namespace[1:4])` 前缀，D30）、该会话的 Webhook 记录、会话行（其余表外键级联），并写审计 `session.purged`。删除请求本身写审计 `session.deleted`（操作者是调用方）。每一步都可重复执行，中途失败下一轮从头再来。清理完成后查询会话返回 404。会话已删除时 `SandboxService` 的获取以 `session.terminated` 拒绝，清理期间不会再认领新的沙箱；回收器不扫描已删除的会话（D50 C-10 的 `findIdle`）。
6. **事件归档。** `retention.app.EventArchiver`（默认 1 小时）选出 `last_activity_at` 早于 90 天（`archive-after`）、未删除、没有未结束轮次、数据库中还有未归档事件的会话，在会话行锁内把事件（含已有归档文件中的）按 seq 写成 gzip 的 JSON Lines（每行一个事件信封）到 `rt/{tenant}/{session}/events/archive-{最大 seq}.jsonl.gz`，同一事务删除数据库中这些事件并更新 `events_archive_key`、`events_archived_seq`、`events_archived_at`、`events_archive_from`（文件中最早的事件时间），提交后删除被取代的旧文件。`SessionEventReader` 是读取事件的唯一入口（`GET /events` 与 SSE 补发、对账），seq 不超过 `events_archived_seq` 的部分读文件、之后的读数据库，SSE 的订阅范围在内存中按同样的规则过滤；读到的旧键已被取代时重读指针。
7. **事件保留。** 内部接口 `GET /internal/v1/tenants/{id}/quotas` 增加 `event_retention_days`。`retention.app.EventRetentionSweeper`（默认 1 小时）逐租户计算截止时间（当前时间减保留天数），删除数据库中早于它的事件，归档文件中早于它的事件被剔除（全部过期时删除文件、`events_archive_key` 置空，`events_archived_seq` 保留，seq 不超过它的事件已不存在）；会话记下已执行到的截止时间（`events_expired_before`），之后只有新活动的会话才再被扫描。一个租户一次清理有删除时写一条审计 `session_events.expired`（资源是租户，`details` 带截止时间、保留天数、删除的事件数与归档文件数）。读不到某个租户的保留天数时跳过该租户。
8. **出网授权的撤销（D50 C-11）。** 删除与归档会话的事务里撤销该会话 Pod IP 的出网授权（各节点按源 IP 缓存 30 秒，终止的会话不应再出网）；`DatabaseSandboxStates.save`（内核保存句柄，IP 可能变化）与 `clear` 同样在同一事务里交给 `EgressRevocations`。

## 影响

- 架构设计：「核心表」的 `sessions` 增加归档与保留的列，新增 `webhook_events`、`webhook_deliveries`，control-plane 的 `webhooks` 写明列；「数据保留」写清归档与保留的执行方式、删除的完成判定；「授权」补 Webhook 的操作类别；「资源」增加 Webhook 配置与投递记录；「Webhook」补投递细则；「ID 规则」增加 `whk`、`whd`；版本升至 v1.23。
- 规范：管理 API 新增 `Webhooks` 标签；公开 API 新增 `Webhooks` 标签（投递记录）；内部接口新增 `InternalWebhooks` 标签与 `TenantQuota.event_retention_days`。
- 迁移：control-plane `V0005__webhooks.sql`；runtime `V0010__webhooks_archive_retention.sql`。
- engine-spi：`SandboxDriver` 增加 `deleteSessionSandboxes(sessionId)`（签名只有平台类型）。
- 验证：`WebhookDeliveryIT`（WireMock 接收方：签名、退避、去重、重投）、`SessionPurgeIT` 与 `SessionPurgeKindIT`、`EventArchiveIT`（列表与 SSE 补发）、`EventRetentionIT`（可控时钟）、control-plane 的 `WebhookAdminIT` 与角色矩阵。
- 剩余：存活会话的无主 `SandboxClaim`（D37 的其余部分）需要按集群全量比对，留给 T28；签名密钥不支持轮换，需要时删除后重建。
