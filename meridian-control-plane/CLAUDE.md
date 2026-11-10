# meridian-control-plane

控制面应用（Spring MVC + 虚拟线程，schema `cp`）：T14a 起有租户、租户设置、项目、成员、角色绑定、API Key 的管理 API（`/admin/v1/`，只接受 OIDC 令牌），给 runtime 与 knowledge 的内部接口（API Key 校验、租户配额、控制台用户的项目权限），T14b 起签发服务 JWT（`/oauth2/token`、`/oauth2/jwks`）。模型目录、凭据、智能体、预算、用量与审计查询在后续任务加入。不执行智能体，不读写 `rt`、`kb`。

## 命令

- 编译与单测：`./mvnw -pl meridian-control-plane -am test`
- 集成测试（Testcontainers：PostgreSQL、Redis、Keycloak）：`./mvnw -pl meridian-control-plane -am verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false`；单个再加 `-Dit.test=<类名> -Dfailsafe.failIfNoSpecifiedTests=false`
- 角色矩阵（T14a-A02、T14b-A04）：`-Dit.test=RoleMatrixIT`，表在 `src/test/resources/rbac/role-matrix.psv`，由 Keycloak 签发的 OIDC 令牌驱动。服务 JWT：`ServiceTokenIT`；项目权限：`ProjectAccessIT`；默认 `sub` 声明：`OidcSubjectClaimIT`
- 本地启动：`scripts/dev/up.sh`、Compose `auth` profile 后 `scripts/dev/run-control-plane.sh`（`dev` profile，端口 18081）。管理 API 的令牌：`scripts/dev/console-token.sh dev-admin`（组 `meridian-platform-admins`，平台管理员）；`scripts/dev/dev-api-key.sh` 建开发租户、项目并签发 Key
- 迁移：`src/main/resources/db/migration/cp/`（`V0001` 租户体系、outbox、幂等键）；已合入的迁移不改
- 规范：管理 API `meridian-api/.../meridian-admin-v1.yaml`（生成到 `controlplane.openapi.admin`），内部接口生成 `InternalApiKeys`、`InternalTenants`、`InternalServiceTokens`、`InternalProjectAccess` 四个标签（`controlplane.openapi.internal`）；改规范后先编译

## 包地图

- 上下文：`tenant`（租户、配额、设置、`ConfigInvalidations` 发布 `config.invalidate`）、`project`（`app.ProjectDirectory` 按项目 ID 找租户）、`member`（成员、角色绑定、`DatabaseGrantSource` 实现 `security.rbac.GrantSource`，平台管理员按 `platform-admins` 或 `platform-admin-groups`；`app.ProjectAccessQuery` 与 `api.InternalProjectAccessApiController` 是 `project-access:resolve`，作用域经 `rbac.PublicApiScopes` 换算）、`servicetoken`（ADR-0044：`app.ServiceTokenIssuer` 认证客户端并签发 RS256 令牌；`app.SigningKeySource` 是密钥的扩展点，开发实现 `infra.DirectorySigningKeys`；`api.ServiceTokensApiController`；装配与本服务的进程内校验器在 `config.ServiceTokenConfiguration`）、`apikey`（`ApiKeyService` 管理、`ApiKeyVerifier` 内部校验、`ApiKeyRevocations` 发布 `key.revoked`）、`audit.app.AdminAudit`（outbox 主题 `audit`）。
- `web`：`AdminRequests`（写操作的 `Idempotency-Key`，作用域"租户 + 控制台用户"，创建租户时为 `platform`）、`Scopes`（构造 `PlatformContext`）、`AfterCommit`、`Times`。
- 删除的级联走进程内事件、同一事务：`TenantDeleted` → `ProjectService` 删全部项目 → `ProjectDeleted` → `ApiKeyService` 吊销 Key、`MemberService` 删项目级角色绑定。用事件是为了避免 tenant → member → project → apikey 的循环依赖。
- 仓储：`tenants`、`tenant_settings`、`projects`、`members`、`role_bindings` 是租户级，`api_keys` 是项目级；跨租户的只有 `tenant.infra.system.TenantDirectoryRepository`（列出租户）、`apikey.infra.system.ApiKeyLookupRepository`（按哈希查找）与 `project.infra.system.ProjectDirectoryRepository`（按项目 ID 找租户）。

## 不变量

- 用例自己绑定 `PlatformContext`（`PlatformContexts.call(Scopes.tenant(...), ...)`）与事务（`TransactionTemplate`）；处理器只做 `@RequiresPermission` 声明与 decode → call → map，`/admin/` 下漏掉注解的处理器由拦截器拒绝（ADR-0042）。
- 角色判断只经 `AccessContext`（同一套 `RoleMatrix`）：授予、撤销所有者与移除所有者成员另需 `OWNER_ROLE_MANAGE`，`RoleBindingRepository.lockOwners` 锁住所有者再计数，租户至少保留一位。
- 已删除的租户中没有任何角色（`DatabaseGrantSource` 先查租户是否存在），删除之后的管理操作都是 403。
- API Key 明文只出现在创建响应（与它的幂等重放）中；库、审计、日志只有 SHA-256 与 `mk_live_` 之后的前 8 位。作用域与限流创建后不能改。
- Pub/Sub（`key.revoked`、`config.invalidate`）在事务提交后发布，失败只记 WARN；只改保留天数不发布配额变更。
- 内部接口的处理器首行 `ServiceCredentialFilter.require(request, 调用方…)`：本服务的接口只接受 `runtime`、`knowledge`。令牌端点与 JWKS 不在 `/internal/` 下，令牌端点自己认证客户端（未登记的服务名也比较一次摘要）。
- 服务客户端在 `meridian.control-plane.service-tokens.clients` 登记（密钥至少 16 个字符，启动时只留 SHA-256），每个客户端只能换取列出的目标服务；签名密钥目录 `service-tokens.key-directory` 必填，多副本必须共用（生产改密钥管理系统，D58）。

## 易错点

- `TenantScopedRepository` 的子类不能手动设置名为 `tenant_id`、`project_id` 的参数（`ScopedStatement.param` 拒绝）：租户级仓储里要按项目过滤时换个参数名（`:project_filter`、`:binding_project_id`）。
- JSON 消息用 record 序列化（字段顺序固定），不要用 `Map.of`：顺序不定，订阅方或测试按原文比较会失败。
- 测试身份（`support.ControlPlaneTestBase`）：控制台用户是 Keycloak 中按名字动态创建的用户（`token(名字)`，`platform` 在平台管理员组），`application-test.yml` 把用户标识设为 `preferred_username`；要换声明配置的测试类用自己的 `@DynamicPropertySource` 覆盖（`OidcSubjectClaimIT`），基类不能注册这些属性，否则子类覆盖不了。内部接口用 `serviceToken()`（经本应用的令牌端点换取）；签名密钥目录在同一 JVM 的全部上下文间共用。
- 测试跑在 Testcontainers 的共享库上，用 `TenantFixture.create` 新建租户隔离；Redis 订阅在监听容器中异步生效，断言前先等约 200 毫秒。
- `@Bean` 之外的组件用 `@Service`/`@Repository` 注解扫描；`application-test.yml` 在 `src/test/resources`（不放 `src/main`，D54 Q-L6）。

## 对外接口

- 管理 API（控制台，T21 起）：`meridian-admin-v1.yaml`，只接受 OIDC 令牌（`security.oidc.OidcTokenVerifier`）。
- 内部接口：`POST /internal/v1/api-keys:verify`（按哈希，404 表示无效）、`GET /internal/v1/tenants/{tenant_id}/quotas`、`POST /internal/v1/project-access:resolve`（`{subject, project_id}` → 租户与作用域，项目不存在 404）；调用方经 `security.servicejwt.ServiceRestClients`。服务 JWT：`POST /oauth2/token`（HTTP Basic，`grant_type=client_credentials&audience=…`）、`GET /oauth2/jwks`。
- Pub/Sub：`key.revoked`（Key ID）、`config.invalidate`（`{"type":"tenant_quota","tenant_id":…}`）。审计：outbox `audit`，`{action, occurred_at, tenant_id, project_id, actor, resource_type, resource_id, details}`，消费者在 T16。
- 给 T15b：控制面的公开 API（`/v1/agents` 等）需要 MVC 版的 API Key 过滤器与限流（当前只有 WebFlux 版），`tenant_settings` 的下发也在 T15b。
