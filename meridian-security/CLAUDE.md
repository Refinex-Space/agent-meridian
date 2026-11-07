# meridian-security

Web 入口与认证的公共能力。T02 已实现 `web` 包：请求 ID 与 problem+json 映射；API Key、OIDC、服务 JWT 与 `PlatformContext` 填充由 T07、T14a、T14b 加入。不访问数据库。

## 命令

- 编译与单测：`./mvnw -pl meridian-security -am test`
- 单个测试：`./mvnw -pl meridian-security -am test -Dtest=<类名> -Dsurefire.failIfNoSpecifiedTests=false`

## 包地图

- `web.ProblemMapper`：唯一的映射逻辑，输出有序 Map（`type`、`title`、`status`、`detail`、`instance`、`code`、`request_id`，校验失败另有 `errors`），并在这里记录异常日志（5xx ERROR、4xx DEBUG）。
- `web.MvcProblemHandler`（`@RestControllerAdvice`）与 `web.WebFluxProblemHandler`（`WebExceptionHandler`，order −2）只做传输。
- `web.RequestIdFilter`（Servlet）与 `web.RequestIdWebFilter`（WebFlux）：最高优先级，写请求属性、响应头、MDC 或 Reactor Context。
- `web.RequestIdMdcAccessor`：WebFlux 中把 Reactor Context 的 `request_id` 恢复到 MDC，需要 Reactor 自动上下文传播。
- `web.SecurityWebAutoConfiguration`：按应用的 Web 栈装配。

## 不变量

- 两种 Web 栈输出的 problem+json 完全相同，由 `ProblemAdaptersTest` 逐字段比较（架构设计「API 设计」、ADR-0020）。
- 5xx 的 detail 固定为不含内部信息的说明；框架异常的 detail 取错误码标题。
- `spring-webmvc`、`spring-webflux`、servlet API、context-propagation 都是可选依赖，应用只带自己的 Web 栈。

## 易错点

- WebFlux 的路由不存在、过滤器异常不经过 `@RestControllerAdvice`，所以 WebFlux 适配器必须是 `WebExceptionHandler`。
- 不要返回 Spring 的 `ProblemDetail`：两种 Web 栈的 JSON 编码配置不同，字段会不一致。
- 传入的 `X-Request-Id` 只接受不超过 128 个字符的字母、数字与 `._:-`，否则重新生成。

## 对外接口

三个应用通过自动配置获得请求 ID 与 problem+json；业务代码只抛 `common.error` 中的异常。
