# meridian-security

Web 入口与认证的公共能力：请求 ID、`traceparent`、problem+json 映射（`web`），API Key 认证、`PlatformContext` 填充与作用域检查（`apikey`，T07）。OIDC 与服务 JWT 由 T14b 加入。不访问数据库。

## 命令

- 编译与单测：`./mvnw -pl meridian-security -am test`
- 单个测试：`./mvnw -pl meridian-security -am test -Dtest=<类名> -Dsurefire.failIfNoSpecifiedTests=false`

## 包地图

- `web.ProblemMapper`：唯一的映射逻辑，输出有序 Map（`type`、`title`、`status`、`detail`、`instance`、`code`、`request_id`，校验失败另有 `errors`），并在这里记录异常日志（5xx ERROR、4xx DEBUG）。
- `web.MvcProblemHandler`（`@RestControllerAdvice`）与 `web.WebFluxProblemHandler`（`WebExceptionHandler`，order −2）只做传输。
- `web.RequestIdFilter`（Servlet）与 `web.RequestIdWebFilter`（WebFlux）：最高优先级，写请求属性、响应头、MDC 或 Reactor Context。
- `web.RequestIdMdcAccessor`：WebFlux 中把 Reactor Context 的 `request_id` 恢复到 MDC，需要 Reactor 自动上下文传播。
- `web.TraceParentWebFilter`（WebFlux）：合法的 `traceparent` 原样返回，trace-id 进 MDC 的 `trace_id`；跨服务传播在 T27。
- `web.SecurityWebAutoConfiguration`：按应用的 Web 栈装配。
- `apikey`：`ApiKeyResolver` 是 API Key 的来源端口（可阻塞，T14a 提供正式实现）；`ApiKeyAuthenticator` 解析 `Authorization: Bearer`；`ApiKeyWebFilter` 认证 `/v1/` 下的请求并写入 `PlatformContext` 与主体（交换属性）；`ApiKeyAuthorization.require` 检查作用域；`ApiKeyAutoConfiguration` 选择解析器。

## 不变量

- 两种 Web 栈输出的 problem+json 完全相同，由 `ProblemAdaptersTest` 逐字段比较（架构设计「API 设计」、ADR-0020）。
- 5xx 的 detail 固定为不含内部信息的说明；框架异常的 detail 取错误码标题。
- `spring-webmvc`、`spring-webflux`、servlet API、context-propagation 都是可选依赖，应用只带自己的 Web 栈。

- `DevApiKeyResolver` 只能在 `dev`、`test` profile 启用（`meridian.security.dev-api-keys.enabled`），其他 profile 下启用则启动失败；没有任何解析器时一律拒绝（ADR-0030）。只保存 API Key 的 SHA-256，不记录明文。

## 易错点

- 方法参数（查询参数、请求头）的约束校验失败是 `HandlerMethodValidationException`，映射为 `request.validation_failed`，字段名取 HTTP 中的名字；生成接口不能带类级 `@Validated`，否则会走 AOP 抛 `ConstraintViolationException` 变成 500（ADR-0029）。

- WebFlux 的路由不存在、过滤器异常不经过 `@RestControllerAdvice`，所以 WebFlux 适配器必须是 `WebExceptionHandler`。
- 不要返回 Spring 的 `ProblemDetail`：两种 Web 栈的 JSON 编码配置不同，字段会不一致。
- 传入的 `X-Request-Id` 只接受不超过 128 个字符的字母、数字与 `._:-`，否则重新生成。

## 对外接口

三个应用通过自动配置获得请求 ID、problem+json 与 API Key 认证；业务代码只抛 `common.error` 中的异常，处理器调用 `ApiKeyAuthorization.require`。错误码 `auth.unauthenticated`、`auth.invalid_api_key`、`auth.insufficient_scope`。
