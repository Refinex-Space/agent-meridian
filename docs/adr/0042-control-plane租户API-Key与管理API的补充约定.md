# ADR-0042 control-plane 租户、API Key 与管理 API 的补充约定

| 项 | 内容 |
|---|---|
| 状态 | 已采纳（补充） |
| 日期 | 2026-11-09 |
| 决策人 | T14a |
| 影响的架构设计章节 | 系统组成（外部请求的路由）、ID 规则、核心表（control-plane）、安全架构（认证、授权）、API 设计（约定、资源）、跨服务数据流、认领（租户并发计数行）、本地开发 |

## 背景

T14a 建立 control-plane 与租户体系。架构设计规定了表名、角色、API Key 只存哈希、校验缓存 60 秒、吊销经 Pub/Sub 立即失效、按 Key 限流的响应头、租户并发配额经内部接口下发，但没有规定：控制台使用的管理接口放在哪份规范、走什么路径；谁能创建租户；成员与角色绑定的 ID；API Key 的明文格式与"前 8 位"指哪一段；runtime 怎样向 control-plane 校验 Key；限流的算法与参数来源；配额的读取时机与 `config.invalidate` 的消息格式。这些决定影响 runtime、control-plane、之后的 knowledge 与控制台，回退成本高，所以记录下来。

## 候选方案

1. **管理 API 的位置**：(a) 与公开 API 同在 `meridian-v1.yaml` 与 `/v1/` 下，按操作标注认证方式；(b) 单独的 `meridian-admin-v1.yaml`，路径前缀 `/admin/v1/`。(a) 让认证过滤器必须逐个路径判断"这是公开接口还是管理接口"，新增接口时容易放错；SDK 也会把控制台专用的接口一起生成出去。(b) 认证按路径首段区分，失败即关闭，SDK 只从公开规范生成。
2. **创建租户的身份**：(a) 引入"平台管理员"，由配置给出 OIDC `sub`；(b) 任何登录用户都能创建租户并成为所有者；(c) 只能由运维脚本直接写库。(b) 在企业内部署中会让任意员工自建租户、自定配额；(c) 违背"新建租户不手工插入数据"。
3. **校验接口传什么**：(a) 明文 Key；(b) SHA-256 哈希。(b) 让明文只出现在调用方的内存中，control-plane 本来就按哈希查找。
4. **限流的位置**：(a) runtime 的 `modelrouting` 已有供应商令牌桶（RPM 加 TPM、返回等待时长），改造后复用；(b) 在 `meridian-security` 中新写一个只按请求计数、返回剩余量与重置时间的令牌桶。两者的语义不同：供应商限流要等待，API Key 限流要立即拒绝并给出 `RateLimit-*`；knowledge（T18）也要用后者，而它不能依赖 runtime。

## 决策

1. **管理 API 单独成规范。** `meridian-api/src/main/resources/openapi/meridian-admin-v1.yaml`，路径前缀 `/admin/v1/`，DTO 生成到 `cn.refinex.meridian.api.admin.model`，control-plane 生成 servlet 接口 `cn.refinex.meridian.controlplane.openapi.admin`。管理 API 只接受控制台用户的身份，不接受 API Key；与控制台同版本发布，不做兼容性检查（与内部接口相同），由 R2 核对一致性。外部请求的路由增加一行：`/admin/v1/*` 进 control-plane。资源按租户嵌套：`/admin/v1/tenants/{tenant_id}/projects/{project_id}/api-keys`，授权从路径取租户与项目，不需要跨租户反查。
2. **控制台身份与角色。**
   - 成员按身份提供方的主体标识（OIDC 的 `sub`）识别，`cp.members` 每个租户一行；角色只来自 `cp.role_bindings`，不来自令牌。T14b 接 OIDC 时只替换"令牌 → 主体"这一步。
   - T14a 的控制台身份来自开发期令牌（`meridian.security.dev-console-users`），与开发期 API Key 一样只允许在 `dev`、`test` profile 启用，其他 profile 启用即启动失败；没有来源时管理 API 一律 401。
   - **平台管理员**由配置 `meridian.control-plane.platform-admins`（主体标识列表）给出，不存数据库。它只能创建租户（同时指定首个所有者）、查看租户、修改租户配额（`max_concurrent_turns`、`event_retention_days`）；配额是平台资源的分配，租户所有者不能改自己的配额。
   - 角色矩阵在 `meridian-security` 的 `rbac` 包中声明：处理器方法标注 `@RequiresPermission`，MVC 拦截器按路径中的租户与项目取该用户的角色绑定后判定；`/admin/` 下没有标注的处理器一律拒绝。租户级角色（所有者、管理员）覆盖租户下所有项目；项目级角色（开发者、观察者、审批人）只作用于绑定的项目，在租户级的只读操作上也算作租户成员。授予或撤销"租户所有者"另需所有者权限，租户至少保留一个所有者。
3. **ID。** 成员 `mbr_`，角色绑定 `rbd_`，补入「ID 规则」。
4. **API Key。**
   - 明文为 `mk_live_` 加 40 位 base62（约 238 位熵），只在创建响应中出现一次；库中存 SHA-256（十六进制）与 `key_prefix`。前缀 `mk_live_` 对所有 Key 相同，所以"前 8 位"指它之后随机部分的前 8 位，日志、事件、审计与列表只显示 `mk_live_` 加这 8 位。
   - 内部校验接口 `POST /internal/v1/api-keys:verify`，请求体只有 `key_hash`；有效时返回租户、项目、作用域、限流与过期时间，无效、已吊销、已过期、所属项目或租户已删除时 404。
   - `meridian-security` 的 `ControlPlaneApiKeyResolver` 调用它，结果（含"无效"）按哈希缓存 60 秒；control-plane 吊销 Key（以及删除项目、租户时吊销其下全部 Key）在提交后经 Pub/Sub `key.revoked` 广播 Key ID，各服务淘汰缓存中该 ID 的条目。消息丢失时最长 60 秒后失效。
   - 管理接口的修改只有名称；作用域与限流在创建时确定，需要变化时创建新 Key 再吊销旧 Key，这样缓存中的主体不会与库不一致。
5. **限流。** 每个 Key 一个 Redis 令牌桶（键 `ratelimit:apikey:{key_id}`），容量与每分钟补充量都是该 Key 的 `rate_limit_per_minute`（创建时不给则取 control-plane 的默认值，默认 600）。每个经过认证的公开 API 请求消耗一个令牌：成功时响应带 `RateLimit-Limit`（容量）、`RateLimit-Remaining`（剩余整数令牌）、`RateLimit-Reset`（补满所需秒数）；不足时 429 `rate_limit.exceeded`，带 `Retry-After`（得到一个令牌所需秒数，至少 1）。实现在 `security.apikey`，应用有 Redis 时启用；Redis 不可用时放行并记 WARN，不让限流的故障扩大为 API 不可用。开发期 API Key 没有限流参数，不限流。
6. **服务间调用。** `security.servicejwt` 提供带超时与服务凭据的 `RestClient` 构造（`ServiceRestClients`），出站凭据经 `ServiceCredentialProvider` 端口取得：T14a 只有开发期静态凭据（`dev`、`test`），T14b 换成服务 JWT。control-plane 的 `/internal/` 由 MVC 版的服务凭据过滤器保护，与 runtime 的 WebFlux 版共用解析器。
7. **租户并发配额。** 内部接口 `GET /internal/v1/tenants/{tenant_id}/quotas`。runtime 只在为租户创建 `rt.tenant_concurrency` 计数行时读取（计数行已存在时不读），未配置 control-plane 地址时（测试）取 `default-tenant-max-running`。control-plane 修改配额后在提交后发布 `config.invalidate`，消息为 `{"type":"tenant_quota","tenant_id":"ten_…"}`；runtime 收到后重新读取并更新已有计数行的 `max_running`。`config.invalidate` 的其他 `type` 由 T15b 起各自的订阅者处理，不认识的类型忽略。
8. **审计。** 管理接口的每次写操作在同一事务中写 outbox 主题 `audit`，载荷 `{action, occurred_at, tenant_id, project_id, actor, resource_type, resource_id, details}`，`actor` 为 `{type: "console_user", subject}`；动作名 `<资源>.<动作>`（如 `api_key.created`、`tenant.quota_updated`）。载荷中的 API Key 只有 ID 与显示前缀。
9. **删除。** 租户与项目是软删除（`deleted_at`），同时吊销其下的 API Key；会话等运行数据的清理不在 T14a。成员与角色绑定直接删除。

## 影响

- 架构设计：外部请求的路由加 `/admin/v1/*`；「ID 规则」加 `mbr`、`rbd`；control-plane 核心表补列；「认证」补控制台身份的开发期来源与平台管理员；「授权」补声明式矩阵；「约定」补限流的参数与算法、管理 API 的规范；「认领」补配额读取时机与 `config.invalidate` 的格式；编码规范的「通用能力复用」表补按 Key 限流、服务间调用客户端与角色矩阵的位置，`security` 的包列表补 `rbac`。
- 代码：`meridian-security` 新增 `rbac`、`oidc`（控制台身份端口与开发实现）、MVC 版服务凭据过滤器、服务间调用客户端、远程 API Key 解析器与限流；`meridian-control-plane` 应用；runtime 改用远程解析器，`DevApiKeyResolver` 只在 `test` profile 可用；`TurnEnqueuer` 经 `TenantQuotaPort` 取配额。
- 风险：Redis 的 Pub/Sub 不保证送达，吊销与配额变更的消息丢失时分别靠 60 秒缓存过期兜底、配额要等下一次变更；runtime 未订阅成功期间修改的配额不会自动纠正，T27 的对账任务可以补上。
