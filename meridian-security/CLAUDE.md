# meridian-security

Web 入口与认证的公共能力：请求 ID、`traceparent`、problem+json 映射（`web`），公开 API 的认证（API Key 或 OIDC 令牌加 `Meridian-Project`）、作用域、限流与 `PlatformContext` 填充（`apikey`），控制台用户的 OIDC 校验（`oidc`）与声明式角色矩阵（`rbac`），服务 JWT 的校验、签发客户端与服务间调用客户端（`servicejwt`）。签发服务 JWT 在 control-plane。不访问数据库（角色绑定由应用经 `GrantSource` 提供）。

## 命令

- 编译与单测：`./mvnw -pl meridian-security -am test`
- 单个测试：`./mvnw -pl meridian-security -am test -Dtest=<类名> -Dsurefire.failIfNoSpecifiedTests=false`
- JWT 相关单测用 `meridian-test-support` 的 `TestTokenIssuer`（本机 HTTP 的 JWKS、发现文档与令牌端点，不需要 Docker）：`ServiceJwtVerifierTest`、`ServiceTokenClientTest`、`OidcTokenVerifierTest`。
- 令牌桶脚本的集成测试（Testcontainers Redis）：`./mvnw -pl meridian-security -am verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=RedisApiKeyRateLimiterIT -Dfailsafe.failIfNoSpecifiedTests=false`

## 包地图

- `web.ProblemMapper`：唯一的映射逻辑，输出有序 Map（`type`、`title`、`status`、`detail`、`instance`、`code`、`request_id`，校验失败另有 `errors`），并在这里记录异常日志（5xx ERROR、4xx DEBUG）。
- `web.MvcProblemHandler`（`@RestControllerAdvice`）与 `web.WebFluxProblemHandler`（`WebExceptionHandler`，order −2）只做传输。
- `web.RequestIdFilter`（Servlet）与 `web.RequestIdWebFilter`（WebFlux）：最高优先级，写请求属性、响应头、MDC 或 Reactor Context。
- `web.RequestIdMdcAccessor`：WebFlux 中把 Reactor Context 的 `request_id` 恢复到 MDC，需要 Reactor 自动上下文传播。
- `web.TraceParentWebFilter`（WebFlux）：合法的 `traceparent` 原样返回，trace-id 进 MDC 的 `trace_id`；跨服务传播在 T27。
- `web.SecurityWebAutoConfiguration`：按应用的 Web 栈装配。
- `web.PrefixAuthenticationFilter`：MVC 版按路径首段认证的基类，失败交给 `handlerExceptionResolver`（过滤器在 `DispatcherServlet` 之前，异常不经过 `@RestControllerAdvice`）；`web.BearerTokens` 解析 `Bearer`。
- `web.RequestPaths.firstSegment`：认证过滤器判断前缀的唯一入口（WebFlux 取 `ServerHttpRequest`，MVC 取 URI 与上下文路径字符串），取路由匹配用的值（解码、去掉矩阵参数）；对原始路径做 `startsWith` 会被 `/internal;a=b/…`、`/%69nternal/…` 绕过（ADR-0041）。
- `servicejwt`（ADR-0044）：入站 `ServiceCredentialResolver` 端口，实现 `ServiceJwtVerifier`（RS256、`iss`、`aud` 等于本服务名、`exp` 容许 30 秒；`remote(...)` 经 `JWKSourceBuilder` 读 control-plane 的 `/oauth2/jwks`，缓存 5 分钟、未知 `kid` 刷新一次、限频；JWKS 读不到抛 503）→ `ServiceCredentialAuthenticator`，WebFlux 的 `ServiceCredentialWebFilter` 与 MVC 的 `ServiceCredentialFilter` 都不写 `PlatformContext`；处理器首行 `ServiceCredentialAuthorization.require(exchange, 调用方…)`（MVC：`ServiceCredentialFilter.require(request, 调用方…)`），调用方不在列表中 403。出站 `ServiceCredentialProvider.credential(audience)`，实现 `ServiceTokenClient`（HTTP Basic 换取，按目标服务缓存到过期前 60 秒，失败 503 不缓存）；`ServiceRestClients.create(baseUrl, audience)`。配置 `ServiceClientProperties`（`control-plane-url`、超时）与 `ServiceJwtProperties`（`meridian.security.service-jwt`：`service`、`issuer`、`client-secret`、`jwks-url`）；服务名常量在 `ServiceJwts`。
- `apikey`：`PublicApiPrincipal`（`ApiKeyPrincipal` 与 `oidc.ConsoleUserPrincipal` 实现），`ApiKeyWebFilter` 按令牌形状分流（`BearerTokens.looksLikeJwt` 为真且有 `ConsoleTokenAuthenticator` 时走控制台用户，要求 `Meridian-Project`）；`ApiKeyResolver` 端口；`ControlPlaneApiKeyResolver` 按 `ApiKeyHashes.sha256` 调 control-plane 的校验接口，结果（含无效）Caffeine 缓存 60 秒，`evict(apiKeyId)` 由应用订阅 `key.revoked`（`REVOKED_CHANNEL`）后调用；`ApiKeyWebFilter` 认证 `/v1/`、按 `ApiKeyRateLimiter`（`RedisApiKeyRateLimiter`，Lua 在 `resources/.../apikey/api_key_rate_limit.lua`）限流并写 `RateLimit-*`，再写入 `PlatformContext` 与主体；`ApiKeyAuthorization.require` 检查作用域。认证与限流在 `PublicApiAdmission`，WebFlux 的 `ApiKeyWebFilter` 与 MVC 的 `ApiKeyFilter`（D55，以 `ScopedValue` 绑定 `PlatformContext`，处理器调 `PublicApiRequests.require`）共用。解析器的选择：应用提供的 > 配置了 control-plane 地址 > 开发期（只在 `test`）> 一律拒绝。
- `oidc`（ADR-0043、ADR-0044）：`OidcProperties`（`meridian.security.oidc`：`issuer`、`audience`、`jwks-uri`、`user-id-claim` 默认 `sub`、`groups-claim` 默认 `groups`、`rate-limit-per-minute`）；`OidcTokenVerifier` 是 `ConsoleUserResolver`（第一次校验时读发现文档，接受 RSA、EC 签名，组声明容忍字符串或数组）；`ConsoleUserFilter` 认证 `/admin/` 并写 `ConsoleUser`（用户标识与组）。公开 API：`ConsoleApiAuthenticator`（实现 `apikey.ConsoleTokenAuthenticator`）→ `ProjectAccessResolver`（`ControlPlaneProjectAccessResolver` 调 `project-access:resolve`，缓存 60 秒）→ `ConsoleUserPrincipal`（桶 `user:{主体}`，`PlatformContext.userId`）。
- `rbac`（ADR-0042）：`Role`、`Permission`（带判定层级；`SESSION_READ`、`SESSION_WRITE`、`MODEL_INVOKE` 是公开 API 的类别；T15b 起 `AGENT_READ`、`AGENT_WRITE`，ADR-0047）、`RoleMatrix`（矩阵的唯一定义）、`PublicApiScopes`（类别到 API Key 作用域的唯一映射，ADR-0044）、`Grant`、`GrantSource` 端口（参数是 `ConsoleUser`，按组判定平台管理员）、`@RequiresPermission`、`PermissionInterceptor`（路由后按路径变量 `tenant_id`、`project_id` 判定，写 `AccessContext`）；应用提供 `GrantSource` 时 `RbacAutoConfiguration` 注册拦截器。

## 不变量

- 两种 Web 栈输出的 problem+json 完全相同，由 `ProblemAdaptersTest` 逐字段比较（架构设计「API 设计」、ADR-0020）。
- 5xx 的 detail 固定为不含内部信息的说明；框架异常的 detail 取错误码标题。
- `spring-webmvc`、`spring-webflux`、servlet API、context-propagation 都是可选依赖，应用只带自己的 Web 栈。
- 没有开发期的服务凭据与控制台令牌（T14b 删除）：没有配置服务名与 JWKS 时内部接口一律拒绝，没有配置 OIDC 签发方时控制台令牌一律拒绝。
- 包依赖单向：`oidc` → `apikey`、`servicejwt`；`rbac` → `oidc`、`apikey`；`apikey` 不依赖 `oidc`（`ConsoleTokenAuthenticator` 放在 `apikey`，自动配置的顺序用 `afterName`）。
- `DevApiKeyResolver` 只能在 `test` profile 启用（T14a-A05，`meridian.security.dev-api-keys.enabled`），其他 profile 下启用则启动失败；没有任何解析器时一律拒绝（ADR-0030、ADR-0042）。只保存 Key 的 SHA-256，不记录明文与令牌；`ServiceJwtProperties.toString` 不含客户端密钥。
- 限流只对带 `rateLimitPerMinute` 的主体生效（开发期 Key 不限流），Redis 键是 `ratelimit:` 加主体的桶名；限流器出错放行并记 WARN，不扩大为 API 不可用。
- control-plane 不可用时 `ControlPlaneApiKeyResolver` 抛 503 且不缓存；404 缓存为"无效"。

## 易错点

- 方法参数（查询参数、请求头）的约束校验失败是 `HandlerMethodValidationException`，映射为 `request.validation_failed`，字段名取 HTTP 中的名字；生成接口不能带类级 `@Validated`，否则会走 AOP 抛 `ConstraintViolationException` 变成 500（ADR-0029）。
- WebFlux 的路由不存在、过滤器异常不经过 `@RestControllerAdvice`，所以 WebFlux 适配器必须是 `WebExceptionHandler`。
- 不要返回 Spring 的 `ProblemDetail`：两种 Web 栈的 JSON 编码配置不同，字段会不一致。
- Nimbus 的 `DefaultJWTClaimsVerifier` 只检查 `aud` 包含受众；JWKS 的限频是每 30 秒至多两次请求，测试轮换要在同一个校验器上先校验一次旧令牌。
- 传入的 `X-Request-Id` 只接受不超过 128 个字符的字母、数字与 `._:-`，否则重新生成。

## 对外接口

三个应用通过自动配置获得请求 ID、problem+json、公开 API 认证与服务 JWT；业务代码只抛 `common.error` 中的异常，处理器调用 `ApiKeyAuthorization.require`（返回 `PublicApiPrincipal`）。MVC 服务的公开 API 经 `ApiKeyFilter` 认证与限流（T15b 起 control-plane 的 `/v1/agents`；应用提供自己的 `ApiKeyResolver` 与 `ProjectAccessResolver` 时优先，control-plane 用进程内的实现）。错误码 `auth.unauthenticated`、`auth.invalid_api_key`、`auth.insufficient_scope`、`auth.invalid_service_credential`、`auth.invalid_console_token`、`auth.project_required`（400）、`rate_limit.exceeded`（429）；control-plane 的令牌端点另有 `auth.invalid_service_client`。knowledge（T18）用 `meridian.security.service-jwt.service=knowledge` 与客户端密钥，MVC 的 `ApiKeyFilter` 加 `ControlPlaneApiKeyResolver`；操作类别另有 `KNOWLEDGE_READ`、`KNOWLEDGE_WRITE`（`knowledge:*` 作用域，ADR-0051）。
