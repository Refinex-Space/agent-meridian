# ADR-0044 OIDC 令牌认证与服务 JWT 的补充约定

| 项 | 内容 |
|---|---|
| 状态 | 已采纳（补充） |
| 日期 | 2026-11-09 |
| 决策人 | T14b |
| 影响的架构设计章节 | 技术基线（身份）、身份映射（`PlatformContext`）、安全架构（认证、授权）、API 设计（约定）、沙箱出网、本地开发 |

## 背景

T14b 让控制台用户经 OIDC 登录、服务之间用 control-plane 签发的短期 JWT 互相调用（ADR-0043 定了"只认标准 OIDC"）。架构设计规定了服务 JWT 的算法、有效期与 audience，规定了控制台调用公开 API 时带 `Meridian-Project` 头、按用户在项目中的角色授权，没有规定：

- 服务怎样向 control-plane 证明自己的身份来换取 JWT（信任的起点）；JWKS 放在哪里、密钥怎样保存与轮换。
- 出网代理（Envoy 的 Lua 过滤器）不能签名，它回调 runtime 用什么凭据（登记事项 D38）。
- runtime 没有角色绑定（在 `cp` schema），OIDC 用户调用公开 API 时怎样取得"该用户在项目中能做什么"；处理器按 API Key 作用域检查，用户没有 Key；限流按什么主体计。
- 用什么库校验 JWT；开发期的静态服务凭据与开发期控制台令牌是否保留。

## 候选方案

1. **服务换取 JWT 的方式**：(a) 每个服务持有 control-plane 的签名私钥自签——违背"control-plane 签发"，私钥散落各处；(b) Kubernetes 投射的 ServiceAccount 令牌经 TokenReview 换取——本机与 Compose 没有 Kubernetes，开发与生产两套机制；(c) OAuth2 客户端凭据模式：每个服务一个客户端密钥，只用于在 control-plane 的令牌端点换取 5 分钟的 JWT，资源服务只认 JWT。
2. **出网代理的凭据**：(a) runtime 为代理单独校验一个长期静态凭据——又回到静态凭据，且凭据可以访问全部内部接口；(b) 边车刷新 JWT 写文件、Lua 每次读文件——多一个容器与镜像；(c) Lua 自己按客户端凭据模式换取 JWT，按 worker 缓存到过期前一分钟。Envoy 的 Lua 全局变量在每个 worker 内跨请求保留（官方文档 lua_filter："globals … outlive a request"，按 worker 隔离），`httpCall` 同步执行，可以在一次 `envoy_on_request` 中先取令牌再回调。
3. **公开 API 的用户授权**：(a) runtime 直接读 `cp.role_bindings`——违背"服务只读写自己的 schema"；(b) control-plane 提供内部接口，按用户与项目返回该用户在公开 API 上的作用域，runtime 缓存结果；角色到作用域的映射与角色矩阵在同一处定义。
4. **JWT 库**：(a) Spring Security 的资源服务器——平台的认证是按路径首段的自研过滤器（ADR-0041、ADR-0042），引入 Spring Security 要整体改用它的过滤器链；(b) 直接用 Nimbus JOSE + JWT（Spring Security 自己也基于它），JWKS 的缓存、未知 `kid` 刷新与限频由它的 `JWKSourceBuilder` 提供，不自写。

## 决策

1. **服务 JWT 用 OAuth2 客户端凭据模式换取（1c）。**
   - control-plane 提供 `POST /oauth2/token`（`application/x-www-form-urlencoded`，`grant_type=client_credentials`、`audience=<目标服务>`，客户端认证用 HTTP Basic `服务名:密钥`）与 `GET /oauth2/jwks`。两者不在 `/internal/` 下：令牌端点自己认证客户端，JWKS 是公钥。错误仍是 problem+json（客户端认证失败 401 `auth.invalid_service_client`，目标服务不在该客户端的允许列表中 403 `access.forbidden`）。
   - 客户端在 `meridian.control-plane.service-tokens.clients` 中登记：服务名、密钥（来自环境变量或 Secret，启动时只保留 SHA-256，常量时间比较）、允许的目标服务。客户端密钥只出现在令牌端点，资源服务的 `/internal/` 只接受 JWT。
   - JWT：`RS256`，头带 `kid`；声明 `iss`（默认 `meridian-control-plane`）、`sub`（调用方服务名）、`aud`（目标服务名，单值）、`iat`、`exp`（5 分钟）、`jti`。资源服务校验签名、`iss`、`aud` 等于自己的服务名、`exp`（容许 30 秒时钟偏差）。
   - 签发客户端 `ServiceTokenClient` 按目标服务缓存令牌，剩余有效期不足 60 秒时刷新；`ServiceRestClients.create(baseUrl, audience)` 每个请求取一次令牌。
   - 校验端 `ServiceJwtVerifier` 经 HTTP 读取 control-plane 的 JWKS（`JWKSourceBuilder`：缓存 5 分钟，遇到未知 `kid` 立即刷新一次，刷新限频）；control-plane 自己用进程内的密钥集校验，不经 HTTP。
   - 密钥：`SigningKeySource` 是扩展点。开发实现 `DirectorySigningKeys` 在配置的目录（Compose 中是命名卷）里没有密钥时生成 RSA 2048 密钥对并写成 JWK 文件，有多个时最新的签名、全部发布到 JWKS；`rotate()` 生成新密钥并只保留上一把（旧令牌最多再有效 5 分钟）。生产从密钥管理系统加载，写进部署文档的待办（T28）。
2. **出网代理用同一套客户端凭据（2c）。** 代理是服务 `egress-proxy`，只允许换取 `audience=runtime` 的令牌；Lua 按 worker 缓存令牌，令牌取不到时拒绝出网（失败即拒绝）。chart 增加回调 control-plane 的集群与网络策略，Secret 改为客户端密钥。
3. **内部接口按调用方服务限定。** 处理器的认证检查带允许的调用方：control-plane 的 `api-keys:verify`、`tenants/{id}/quotas`、`project-access:resolve` 只接受 `runtime`、`knowledge`；runtime 的 `egress:authorize` 只接受 `egress-proxy`，`embeddings`、`rerank` 只接受 `knowledge`。泄露的代理密钥只能做出网授权查询。
4. **公开 API 接受 OIDC 令牌（3b）。**
   - runtime 的公开 API 过滤器按令牌形状分流：三段的 JWT 走 OIDC，其他按 API Key。OIDC 令牌必须带 `Meridian-Project`（缺少时 400 `auth.project_required`），runtime 以服务 JWT 调 control-plane 的 `POST /internal/v1/project-access:resolve`（`{subject, project_id}`）得到租户与作用域；项目不存在、已删除或用户没有作用域时 403 `access.forbidden`，不区分两者。结果按（主体，项目）缓存 60 秒，角色变更最长 60 秒后生效（与 API Key 的缓存相同）。
   - 角色到公开 API 作用域的映射在 `security.rbac` 中与角色矩阵一起定义：新增操作类别 `SESSION_READ`（`sessions:read`）、`SESSION_WRITE`（`sessions:write`）、`MODEL_INVOKE`（`models:invoke`），都是项目级。所有者全部有；租户管理员、项目观察者、审批人只有 `SESSION_READ`；项目开发者（Playground）三个都有。`knowledge:*`、`files:write` 在对应能力上线时（T18、T17b）再映射。
   - 主体：`ApiKeyPrincipal` 与新增的 `ConsoleUserPrincipal` 实现 `PublicApiPrincipal`，处理器仍用 `ApiKeyAuthorization.require(exchange, scope)`。`PlatformContext` 增加 `userId`（控制台用户的主体标识），`actor()` 返回 API Key ID 或 `user:<主体>`，幂等键作用域、待处理动作的 `decided_by` 用它。
   - 限流：控制台用户按主体限流，桶键 `ratelimit:user:{主体}`，上限 `meridian.security.oidc.rate-limit-per-minute`（默认 600），与 API Key 用同一个令牌桶脚本与响应头。
5. **OIDC 校验（ADR-0043 的落实）。** 配置 `meridian.security.oidc`：`issuer`（与令牌的 `iss` 逐字相等）、`audience`（`aud` 必须包含它）、`jwks-uri`（可选，缺省时从 `issuer` 的发现文档读取；容器内访问 IdP 的地址与 `iss` 不同时使用）、`user-id-claim`（默认 `sub`）、`groups-claim`（默认 `groups`，取值可以是字符串或字符串数组，取不到时为空）。发现文档在第一次校验时读取，IdP 晚于服务启动也能工作。签名算法接受 JWKS 中 RSA 与 EC 密钥对应的 `RS256/384/512`、`PS256/384/512`、`ES256/384/512`。控制台客户端 ID 是控制台（T21）的配置，后端只校验 `aud`。平台管理员另可按组配置 `meridian.control-plane.platform-admin-groups`。
6. **开发期身份全部移除。** `DevServiceCredentialResolver`、`meridian.security.dev-service-credentials`、`meridian.security.service-client.credential`、`DevConsoleUserResolver`、`meridian.security.dev-console-users` 与 `.env` 中的 `MERIDIAN_DEV_SERVICE_CREDENTIAL`、`MERIDIAN_DEV_CONSOLE_TOKEN` 删除。本地开发用 Compose 的 `auth` profile（Keycloak，realm `meridian-dev`）登录，服务之间用 `.env` 中各服务的客户端密钥换取 JWT。开发期 API Key（只在 `test`）不变。
7. **Keycloak 参考配置。** realm 文件 `deploy/keycloak/meridian-dev-realm.json`：控制台客户端 `meridian-console`（公开客户端，授权码加 PKCE S256）、只给开发脚本与测试用的 `meridian-dev-cli`（公开客户端，直接授权）；两者都经受众映射器把 `meridian` 加入 `aud`、经组映射器输出 `groups`（组名，不带路径）。测试用户的口令是公开的开发口令，不是真实凭据。集成测试用 Testcontainers 的 Keycloak 模块（`com.github.dasniko:testcontainers-keycloak`），测试用户经管理 REST API 动态创建，两种声明配置都有用例：默认 `sub` 加主体列表（`OidcSubjectClaimIT`、runtime 的 `ConsoleUserApiIT`），`preferred_username` 加按组的平台管理员（`RoleMatrixIT` 等 control-plane 测试）。

## 影响

- 架构设计：「技术基线」身份一行补 Nimbus；「身份映射」`PlatformContext` 增加用户；「认证」服务之间一行写明换取方式、JWKS 与轮换，控制台一行写明公开 API 的作用域来源；「授权」写明公开 API 的作用域映射；「约定」增加 `Meridian-Project`；「沙箱出网」回调凭据；「本地开发」`auth` profile 的端口与 realm。版本号递增。
- 代码：`meridian-security`（`servicejwt`、`oidc`、`apikey`、`rbac`）、`meridian-common`（`PlatformContext`）、control-plane（`servicetoken` 上下文、`project-access:resolve`、`DatabaseGrantSource` 按组判定平台管理员）、runtime（配置、调用方限定、`actor()`）、`meridian-internal-v1.yaml`、`meridian-v1.yaml`（安全方案说明）、chart 与开发脚本。没有数据迁移。
- 依赖：`com.nimbusds:nimbus-jose-jwt`（`meridian-security`、`meridian-test-support`）与测试用的 `testcontainers-keycloak`，版本在 `meridian-bom`。
- 风险：客户端密钥是长期凭据，泄露后能在吊销前换取令牌，所以它只出现在令牌端点、按服务限定目标，生产由 Secret 管理系统下发并定期更换；各家 IdP 的 `aud`、组声明格式不同（ADR-0043），由 `groups-claim` 与"字符串或数组"的容忍处理，部署文档逐家说明（T26、T28）。
