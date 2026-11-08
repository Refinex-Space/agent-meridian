# P8 MCP Server 的 Servlet 传输

| 项 | 内容 |
|---|---|
| 状态 | 预验证通过（T10b）；定稿待 T19（真实的 `knowledge_search` 与 MCP Inspector） |
| 通过标准 | MCP SDK 0.17.2 的 Servlet 传输在 Spring Boot 4.1 下暴露 MCP Server，用 MCP Inspector 调通 `knowledge_search`（架构设计「M0 技术验证」） |
| 环境 | macOS arm64，Temurin 25.0.5+8；Spring Boot 4.1.2（Spring MVC，内嵌 Tomcat 11.0.27） |
| 版本 | MCP Java SDK 0.17.2（随 AgentScope 2.0.4 引入）；验证于 2026-11-08 |

## 结论（预验证）

knowledge 服务（T19）暴露 MCP Server 的方式可行：`HttpServletStreamableServerTransportProvider` 是一个 `HttpServlet`，用 `ServletRegistrationBean` 注册到 Spring Boot 4.1 的 Spring MVC 应用（`asyncSupported=true`），`McpServer.sync(transport)` 挂上工具后，平台的 MCP 客户端（`McpConnector`，经 AgentScope 2.0.4 的 `McpClientBuilder` 构建）能完成初始化、发现工具并调用；过滤器可以在 Servlet 之前校验 `Authorization` 头。SSE 传输（`HttpServletSseServerTransportProvider`）以同样方式注册，也能调通。

## 做法

`meridian-engine-agentscope-test` 的集成测试 `turn.McpToolsIT`：

- 服务端 `turn.McpTestServer`：`@SpringBootConfiguration` 加 `@EnableAutoConfiguration` 的 Servlet 应用，随机端口。
  - `/mcp/crm`：`HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp/crm").build()`，`new ServletRegistrationBean<>(transport, "/mcp/crm")`，`McpServer.sync(transport).serverInfo(...).capabilities(tools(true)).tools(...)`。
  - `/sse/crm`：`HttpServletSseServerTransportProvider`，映射 `/sse/crm` 与 `/sse/crm/message` 两个路径。
  - `FilterRegistrationBean` 校验 `Authorization: Bearer <令牌>`，不对时 401，并记录每个请求的方法、路径与头。
  - 三个工具：`lookup_order`（返回带伪造闭合标签的文本）、`create_ticket`（记录收到的 `_meta`）、`broken`（返回 `isError`）。
- 客户端是生产的 `McpConnector`，经真实装配的内核（`EngineHarness.withMcp`，`ScriptedModel` 驱动）调用：

| 用例 | 断言 |
|---|---|
| `streamableHttpToolsAreDiscoveredCalledAndCredentialsStayInRequestHeaders` | 三个工具以 `mcp__crm__*` 出现在模型请求中；事件为 `agent.mcp_tool_use`（服务名 `crm`）；结果包上来源标签、伪造的闭合标签被转义；`broken` 的结果是 ERROR、本轮正常结束；未标注副作用等级时按 `non_idempotent` 记账，幂等键 `ses_test:call_ticket` 出现在服务端收到的 `_meta`；服务端收到的每个请求（初始化、工具发现、调用）都带凭据，事件与 DEBUG 级日志中没有凭据 |
| `sseTransportIsSelectedByConfigurationAndEnabledToolsAreFiltered` | 配置为 SSE 时走 SSE 传输；`enabled_tools` 只启用 `lookup_order`，另两个工具不暴露 |
| `rejectedCredentialsEndTheTurnWithARetryableError` | 凭据错误时初始化失败，本轮以 `session.error`（`tool.mcp_unavailable`，`retryable`）加 `status_rescheduled` 结束，不调用模型；错误信息不含凭据 |
| `closingTheInstanceClosesTheMcpClient` | 实例缓存关闭时 MCP 客户端随之关闭 |

```bash
./mvnw -pl meridian-engine-agentscope-test -am verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=McpToolsIT -Dfailsafe.failIfNoSpecifiedTests=false
```

结果：4 个用例通过，整个类约 2 秒。

## 发现

- 2.0.4 的 `McpClientBuilder` 中 `customizeStreamableHttpClient`、`customizeSseClient`、`httpRequestCustomizer`、`header` 都只对已选择的传输生效（`instanceof` 判断），先调用会被静默忽略：必须先 `streamableHttpTransport(url)` 或 `sseTransport(url)`。
- `httpRequestCustomizer` 只接受同步定制器，MCP SDK 用 `Mono.fromSupplier` 包装，在发请求的线程上执行，不能在里面阻塞解析凭据。定制器拿到的 `McpTransportContext` 来自 Reactor Context 的 `MCP_TRANSPORT_CONTEXT` 键：异步客户端把调用方的 Context 沿 `McpClientSession.sendRequest` 一路传到传输层（含初始化握手），同步客户端（`buildSync`）在内部另起订阅，调用方的 Context 传不过去。平台因此用 `buildAsync()`，凭据在调用前解析好放进 Context（ADR-0035）。
- `protocolVersions(...)` 由 `ProtocolVersionOverrideTransport` 覆盖传输声明的版本；初始化之后的请求头 `MCP-Protocol-Version` 取协商结果。Streamable HTTP 声明 `2025-03-26`、`2025-06-18`，SSE 只能是 `2024-11-05`。
- 平台实例缓存的移除回调在执行器上异步执行，关闭实例之后 MCP 客户端的关闭稍晚发生。
- 测试服务要设 `server.shutdown=immediate`：默认的优雅关闭会等 SSE 长连接，测试类的收尾因此多等约 30 秒。

## 待完成（T19）

- knowledge 服务以同样方式注册 `/mcp/knowledge`，暴露真实的 `knowledge_search`，按架构设计的认证方式（API Key）校验请求。
- 用 MCP Inspector 调通，补充本报告的定稿部分并把状态改为"通过"。
