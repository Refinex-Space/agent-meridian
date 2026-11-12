# meridian-control-plane

控制面应用（Spring MVC + 虚拟线程，schema `cp`）：T14a 起有租户、租户设置、项目、成员、角色绑定、API Key 的管理 API（`/admin/v1/`，只接受 OIDC 令牌），给 runtime 与 knowledge 的内部接口（API Key 校验、租户配额、控制台用户的项目权限），T14b 起签发服务 JWT（`/oauth2/token`、`/oauth2/jwks`），T15a 起有凭据库（信封加密）、供应商账号、模型目录与价格表（ADR-0045），T15b 起有智能体与版本、环境、技能、MCP 服务、租户与项目策略的管理、给 runtime 的配置分发与公开接口 `GET /v1/agents`（ADR-0047）。预算、用量与审计查询在后续任务加入。不执行智能体，不读写 `rt`、`kb`。

## 命令

- 编译与单测：`./mvnw -pl meridian-control-plane -am test`
- 集成测试（Testcontainers：PostgreSQL、Redis、Keycloak）：`./mvnw -pl meridian-control-plane -am verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false`；单个再加 `-Dit.test=<类名> -Dfailsafe.failIfNoSpecifiedTests=false`
- 角色矩阵（T14a-A02、T14b-A04）：`-Dit.test=RoleMatrixIT`，表在 `src/test/resources/rbac/role-matrix.psv`，由 Keycloak 签发的 OIDC 令牌驱动。服务 JWT：`ServiceTokenIT`；项目权限：`ProjectAccessIT`；默认 `sub` 声明：`OidcSubjectClaimIT`
- 凭据与目录（T15a）：`SecretStoreIT`（明文、密文、数据密钥、审计、引用冲突）、`ModelCatalogIT`（内部解析、价格表版本、失效通知、端点校验）、`VaultTransitIT`（Testcontainers 的 `hashicorp/vault:1.20.4`，整个应用以 `key-encryption=vault` 运行）；真实链路 `scripts/dev/catalog-smoke.sh [--dashscope]`（根 `CLAUDE.md`）
- 智能体配置（T15b）：`agent.AgentVersionIT`（六项发布校验、不可变与对比、解析、公开接口、引用检查）、`agent.AgentResourcesIT`（环境修订、docx 技能包、MCP 探测、策略、租户设置）、`agent.AgentConfigDomainTest`；真实链路 `scripts/dev/agent-smoke.sh [--dashscope]`（根 `CLAUDE.md`）
- 本地启动：`scripts/dev/up.sh`、Compose `auth` profile 后 `scripts/dev/run-control-plane.sh`（`dev` profile，端口 18081）。管理 API 的令牌：`scripts/dev/console-token.sh dev-admin`（组 `meridian-platform-admins`，平台管理员）；`scripts/dev/dev-api-key.sh` 建开发租户、项目并签发 Key
- 迁移：`src/main/resources/db/migration/cp/`（`V0001` 租户体系、outbox、幂等键；`V0002` 凭据、数据密钥、供应商账号、价格表与版本、模型；`V0003` 智能体与版本、版本引用、环境与修订、技能与版本、MCP 服务、权限规则）；已合入的迁移不改
- 规范：管理 API `meridian-api/.../meridian-admin-v1.yaml`（生成到 `controlplane.openapi.admin`），内部接口生成 `InternalApiKeys`、`InternalTenants`、`InternalServiceTokens`、`InternalProjectAccess`、`InternalSecrets`、`InternalModelCatalog`、`InternalAgentConfig`、`InternalPermissionRules` 八个标签（`controlplane.openapi.internal`），公开规范只生成 `Agents`（`controlplane.openapi.pub`）；智能体规格的 Schema 是 `meridian-api` 的 `schema/agent-spec.schema.json`；改规范后先编译

## 包地图

- 上下文：`tenant`（租户、配额、设置、`ConfigInvalidations` 发布 `config.invalidate`）、`project`（`app.ProjectDirectory` 按项目 ID 找租户）、`member`（成员、角色绑定、`DatabaseGrantSource` 实现 `security.rbac.GrantSource`，平台管理员按 `platform-admins` 或 `platform-admin-groups`；`app.ProjectAccessQuery` 与 `api.InternalProjectAccessApiController` 是 `project-access:resolve`，作用域经 `rbac.PublicApiScopes` 换算）、`servicetoken`（ADR-0044：`app.ServiceTokenIssuer` 认证客户端并签发 RS256 令牌；`app.SigningKeySource` 是密钥的扩展点，开发实现 `infra.DirectorySigningKeys`；`api.ServiceTokensApiController`；装配与本服务的进程内校验器在 `config.ServiceTokenConfiguration`）、`apikey`（`ApiKeyService` 管理、`ApiKeyVerifier` 内部校验、`ApiKeyRevocations` 发布 `key.revoked`）、`audit.app.AdminAudit`（outbox 主题 `audit`）。
- `secret`（T15a，ADR-0045）：`domain.SecretCipher`（AES-256-GCM，附加认证数据为租户、凭据 ID 与版本）；`app.KeyEncryptionService` 是主密钥的端口，实现 `infra.LocalMasterKeyEncryption`（开发与测试，文件不存在时生成并 0600）、`infra.VaultTransitKeyEncryption`（RestClient 调 Transit），按 `secrets.key-encryption` 在 `config.SecretConfiguration` 选择；`app.DataKeys` 按租户生成与解包数据密钥（缓存 5 分钟）；`app.SecretService` 管理，`app.SecretResolution` 是 `secrets:resolve`；`infra.SecretReferenceRepository` 直接读 `provider_accounts`、`tenant_settings` 算引用方。
- `catalog`（T15a）：`app.ProviderAccountService`、`ModelService`、`PriceBookService` 管理，`app.ModelCatalogQuery` 经 `infra.system.CatalogLookupRepository`（跨租户，按模型 ID）给 `GET /internal/v1/models/{id}`；变更经 `tenant.app.ConfigInvalidations.resourceChanged` 广播。
- 智能体配置（T15b，ADR-0047）：`agent`（`app.AgentService`、`AgentVersionService`（草稿、发布、对比）、`AgentPublication`（发布前的 Schema、六项校验与快照）、`AgentSpecSchema`（networknt 2.0.1）、`AgentConfigQuery`（内部解析，经 `infra.system.AgentConfigLookupRepository` 跨租户）、`KnowledgeCatalogClient` 与开发实现 `infra.ConfiguredKnowledgeCatalog`（`TODO(T18)`）；`domain.AgentSpecRules`、`SpecDiff`、`SpecReference`；`infra.AgentVersionRefRepository` 是已发布版本的引用，模型、技能、MCP 服务、凭据的删除检查都查它；`api.PublicAgentsApiController` 是 `GET /v1/agents`）、`environment`（修订历史，`domain.EnvironmentConfig` 规范化白名单）、`skill`（`domain.SkillPackage` 校验 frontmatter，`app.SkillStorage` 写对象存储）、`mcp`（`app.McpServerService` 以 `control-plane` 身份解析凭据后探测，`infra.SdkMcpProbe` 用 MCP SDK 0.17.2）、`policy`（`app.PolicyRuleService`：租户与项目策略、记住此决定）。公开接口的认证是 `security` 的 MVC `ApiKeyFilter` 加进程内的 `apikey.app.InProcessApiKeyResolver` 与 `member.app.InProcessProjectAccess`。
- `web`：`AdminRequests`（写操作的 `Idempotency-Key`，作用域"租户 + 控制台用户"，创建租户时为 `platform`）、`Scopes`（构造 `PlatformContext`）、`AfterCommit`、`Times`、`Endpoints`（端点按 `common.net.EndpointPolicy` 校验，不允许时 400 指向字段）、`NumericCursors`（按序号分页的游标）。
- 删除的级联走进程内事件、同一事务：`TenantDeleted` → `ProjectService` 删全部项目 → `ProjectDeleted` → `ApiKeyService` 吊销 Key、`MemberService` 删项目级角色绑定。用事件是为了避免 tenant → member → project → apikey 的循环依赖。
- 仓储：`tenants`、`tenant_settings`、`projects`、`members`、`role_bindings`、`secrets`、`secret_data_keys`、`provider_accounts`、`models`、`price_books`、`agent_version_refs`、`permission_rules` 是租户级（凭据与权限规则带可空的 `project_id`），`api_keys`、`agents`、`agent_versions`、`environments`、`skills`、`mcp_servers` 是项目级；跨租户的只有 `tenant.infra.system.TenantDirectoryRepository`（列出租户）、`apikey.infra.system.ApiKeyLookupRepository`（按哈希查找）、`project.infra.system.ProjectDirectoryRepository`（按项目 ID 找租户）、`catalog.infra.system.CatalogLookupRepository` 与 `agent.infra.system.AgentConfigLookupRepository`。

## 不变量

- 用例自己绑定 `PlatformContext`（`PlatformContexts.call(Scopes.tenant(...), ...)`）与事务（`TransactionTemplate`）；处理器只做 `@RequiresPermission` 声明与 decode → call → map，`/admin/` 下漏掉注解的处理器由拦截器拒绝（ADR-0042）。
- 角色判断只经 `AccessContext`（同一套 `RoleMatrix`）：授予、撤销所有者与移除所有者成员另需 `OWNER_ROLE_MANAGE`，`RoleBindingRepository.lockOwners` 锁住所有者再计数，租户至少保留一位。
- 已删除的租户中没有任何角色（`DatabaseGrantSource` 先查租户是否存在），删除之后的管理操作都是 403。
- API Key 明文只出现在创建响应（与它的幂等重放）中；库、审计、日志只有 SHA-256 与 `mk_live_` 之后的前 8 位。作用域与限流创建后不能改。
- Pub/Sub（`key.revoked`、`config.invalidate`）在事务提交后发布，失败只记 WARN；只改保留天数不发布配额变更。
- 内部接口的处理器首行 `ServiceCredentialFilter.require(request, 调用方…)`：本服务的接口只接受 `runtime`、`knowledge`（凭据解析与模型目录只接受 `runtime`）。令牌端点与 JWKS 不在 `/internal/` 下，令牌端点自己认证客户端（未登记的服务名也比较一次摘要）。
- 凭据明文只在 `SecretResolution` 的返回值与 `secrets:resolve` 的响应（`no-store`）中出现：管理响应、审计、日志、幂等记录都没有（写入的幂等指纹不含 `value`）；解析在审计事务提交之后才交出明文。数据密钥在独立事务（`REQUIRES_NEW`）中生成并提交后才进缓存，业务事务之前取得。
- `secrets.key-encryption` 没有默认值（`local` 或 `vault`），`dev` 与测试是 `local`；换了主密钥（文件或服务）后旧的数据密钥解不开（`secret.encryption_unavailable`）。
- 删除被引用的资源抛 `common.error.ReferencedException`（409，problem+json 带 `references`）；外键是并发删除的兜底。jsonb 中的引用（智能体规格）没有外键：发布时把引用写进 `agent_version_refs`，被引用的 MCP 服务、技能共享锁读取、模型排他锁读取，删除方锁行后再查引用。
- 已发布的智能体版本不可变：仓储的写操作都带 `status = 'draft'` 条件；发布把 MCP 服务配置与技能名写进规格快照，MCP 服务的修改在下一次发布时才生效。智能体、环境、技能是软删除，已发布版本、修订、技能版本仍可经内部接口解析（运行中的会话）。端点在写入时解析 DNS 校验：测试只用回环地址（`application-test.yml` 放行回环）。
- 服务客户端在 `meridian.control-plane.service-tokens.clients` 登记（密钥至少 16 个字符，启动时只留 SHA-256），每个客户端只能换取列出的目标服务；签名密钥目录 `service-tokens.key-directory` 必填，多副本必须共用（生产改密钥管理系统，D58）。

## 易错点

- `TenantScopedRepository` 的子类不能手动设置名为 `tenant_id`、`project_id` 的参数（`ScopedStatement.param` 拒绝）：租户级仓储里要按项目过滤时换个参数名（`:project_filter`、`:binding_project_id`）。
- JSON 消息用 record 序列化（字段顺序固定），不要用 `Map.of`：顺序不定，订阅方或测试按原文比较会失败。
- 测试身份（`support.ControlPlaneTestBase`）：控制台用户是 Keycloak 中按名字动态创建的用户（`token(名字)`，`platform` 在平台管理员组），`application-test.yml` 把用户标识设为 `preferred_username`；要换声明配置的测试类用自己的 `@DynamicPropertySource` 覆盖（`OidcSubjectClaimIT`），基类不能注册这些属性，否则子类覆盖不了。内部接口用 `serviceToken()`（经本应用的令牌端点换取）；签名密钥目录在同一 JVM 的全部上下文间共用。
- 测试跑在 Testcontainers 的共享库上，用 `TenantFixture.create` 新建租户隔离；Redis 订阅在监听容器中异步生效，断言前先等约 200 毫秒。
- `@Bean` 之外的组件用 `@Service`/`@Repository` 注解扫描；`application-test.yml` 在 `src/test/resources`（不放 `src/main`，D54 Q-L6）。
- 管理 API 规范里 `format: uri` 加 `maxLength` 会在生成的 `URI` 字段上放 `@Size`，校验时 500：端点字段写成普通字符串（T15a 修了租户设置的 `base_url`）。
- `TenantFixture` 另建凭据、供应商账号、价格表与模型（`secret`、`provider`、`model` 有引用方，`spare_*` 没有），角色矩阵的写操作每行都新建一份；`withAgentConfig` 再建 MCP 服务（探测基类的进程内 `MCP`，令牌 `MCP_TOKEN`）、环境、技能与已发布一版、草稿一版的智能体，角色矩阵只在路径或请求体引用它们时才建。开发期知识库目录只认 `KNOWN_KNOWLEDGE_BASE`。
- control-plane 需要对象存储（技能文件）：测试基类注册 MinIO，`dev` profile 用 compose 的 MinIO。

## 对外接口

- 管理 API（控制台，T21 起）：`meridian-admin-v1.yaml`，只接受 OIDC 令牌（`security.oidc.OidcTokenVerifier`）。
- 配置分发（T15b，只接受 runtime）：`POST /internal/v1/agent-versions/{id}:resolve`、`POST /internal/v1/agents/{id}:resolve`（快照与技能内容）、`GET /internal/v1/environments/{id}[?revision=]`、`GET /internal/v1/skills/{id}/versions/{v}`、`GET|POST /internal/v1/projects/{id}/permission-rules`、`GET /internal/v1/tenants/{id}/settings`；解析不按租户过滤，runtime 校验归属。公开 `GET /v1/agents`（`sessions:read`）。
- 内部接口：`POST /internal/v1/api-keys:verify`（按哈希，404 表示无效）、`GET /internal/v1/tenants/{tenant_id}/quotas`、`POST /internal/v1/project-access:resolve`（`{subject, project_id}` → 租户与作用域，项目不存在 404）、`POST /internal/v1/secrets:resolve` 与 `GET /internal/v1/models/{model_id}`（只接受 runtime，ADR-0045）；调用方经 `security.servicejwt.ServiceRestClients`。服务 JWT：`POST /oauth2/token`（HTTP Basic，`grant_type=client_credentials&audience=…`）、`GET /oauth2/jwks`。
- Pub/Sub：`key.revoked`（Key ID）、`config.invalidate`（`{"type":"tenant_quota","tenant_id":…}`；T15a 起另有 `secret`、`provider_account`、`model`、`price_book`，T15b 起另有 `agent`、`environment`、`skill`、`mcp_server`、`permission_policy`、`tenant_settings`，带 `id`）。审计另有 `secret.*`（`secret.resolved` 的操作者是 `service`）、`provider_account.*`、`model.*`、`price_book.*`。审计：outbox `audit`，`{action, occurred_at, tenant_id, project_id, actor, resource_type, resource_id, details}`，消费者在 T16。
- 资源引用凭据时用 `SecretService.requireUsable(租户, 项目, 凭据, 字段)`（项目级资源可以引用租户级与本项目的凭据），新的引用方加进 `SecretReferenceRepository`。给 T18：知识库的存在性换掉 `ConfiguredKnowledgeCatalog`。给 T22、T23：控制台按 `agent-spec.schema.json` 校验表单，服务端字段错误是 `spec.` 加点号路径。
