# P8 MCP Server 的 Servlet 传输

| 项 | 内容 |
|---|---|
| 状态 | 通过（T10b 预验证，T19 定稿：knowledge 的 `/mcp/knowledge` 暴露真实的 `knowledge_search`，MCP Inspector 调通） |
| 通过标准 | MCP SDK 0.17.2 的 Servlet 传输在 Spring Boot 4.1 下暴露 MCP Server，用 MCP Inspector 调通 `knowledge_search`（架构设计「M0 技术验证」） |
| 环境 | macOS arm64，Temurin 25.0.5+8；Spring Boot 4.1.2（Spring MVC，内嵌 Tomcat 11.0.27） |
| 版本 | MCP Java SDK 0.17.2（随 AgentScope 2.0.4 引入）；预验证于 2026-11-08，定稿于 2026-11-12（MCP Inspector 2.13.0，Node.js 24.14.1） |

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

## 定稿（T19）

### 结论

通过。knowledge 服务（Spring MVC，Tomcat 11.0.27，Java 25.0.5）把 `HttpServletStreamableServerTransportProvider` 注册为 Servlet `/mcp/knowledge`（`config.McpServerConfiguration`），`McpServer.sync` 挂上工具 `knowledge_search`；Servlet 之前的 `web.McpApiKeyFilter` 用与公开 API 相同的准入（API Key 或控制台用户加 `Meridian-Project`，按主体限流），要求 `knowledge:read`。MCP Inspector 的 CLI 模式完成了初始化、工具发现与调用，检索结果按 `Meridian-Acl-Tags` 请求头过滤。

### 做法

本机按根 `CLAUDE.md` 启动：`scripts/dev/up.sh`、Compose `auth` profile 的 Keycloak、`run-control-plane.sh`、`run-runtime.sh --server.port=18080`（`dev` 的文件目录，Embedding `mdl_dev_embedding` 与 rerank `mdl_dev_rerank` 都是 DashScope 的真实模型）、`run-knowledge.sh`。经管理 API 建租户、项目、不限的预算与带 `knowledge:read`、`knowledge:write` 的 API Key，经公开 API 建知识库（设置了 `rerank_model`）并上传检索评测语料中的两份制度：`attendance.md`（不限标签）与 `compensation.md`（`level:confidential`）。

```bash
I="npx -y @modelcontextprotocol/inspector@2.13.0 --cli http://localhost:18083/mcp/knowledge --transport http"
$I --method tools/list --header "Authorization: Bearer $KEY"
$I --method tools/call --tool-name knowledge_search --tool-arg query=年终奖一般发几个月 --tool-arg top_k=3 \
   --header "Authorization: Bearer $KEY"
$I --method tools/call --tool-name knowledge_search --tool-arg query=年终奖一般发几个月 --tool-arg top_k=3 \
   --header "Authorization: Bearer $KEY" --header "Meridian-Acl-Tags: level:confidential"
```

### 输出

`tools/list`（摘录）：

```json
{"tools": [{"name": "knowledge_search", "title": "Knowledge search",
  "inputSchema": {"type": "object", "properties": {"query": {...}, "knowledge_base_ids": {...}, "top_k": {...}},
                  "required": ["query"]},
  "annotations": {"readOnlyHint": true, "destructiveHint": false, "idempotentHint": true, "openWorldHint": false}}]}
```

`tools/call` 的结果是一段 JSON 文本（与公开 API 的 `KnowledgeSearchResult` 相同），`isError` 为 `false`：

| 请求头 | 命中（文件、条款、分数） |
|---|---|
| 只有 `Authorization` | `attendance.md` 第二条 年休假 0.1805；第四条 加班与调休 0.1514；第一条 工作时间 0.0974 |
| 另加 `Meridian-Acl-Tags: level:confidential` | `compensation.md` 第四条 年终奖 0.8873；第一条 薪酬结构 0.5560；第二条 绩效考核 0.2840 |

没有标签的调用方看不到薪酬制度，只能拿到不限标签的考勤条款，分数都很低；带标签时标准答案（第四条 年终奖）排第一。分数是 rerank 模型的得分。

没有凭据时服务端返回 401 problem+json（`curl` 核对）：

```json
{"type":"urn:meridian:error:auth.unauthenticated","title":"未认证","status":401,
 "detail":"请求需要 Authorization: Bearer <API Key>","instance":"/mcp/knowledge","code":"auth.unauthenticated",...}
```

自动化覆盖：`meridian-knowledge` 的 `search.KnowledgeMcpIT` 用 MCP SDK 0.17.2 的客户端初始化、发现与调用，验证标签过滤、错误结果（别的项目的知识库、空查询）、401 与缺少作用域的 403、检索计量的来源 `mcp`。

```bash
./mvnw -pl meridian-knowledge -am verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=KnowledgeMcpIT -Dfailsafe.failIfNoSpecifiedTests=false
```

### 发现

- 访问标签放在请求头 `Meridian-Acl-Tags`，不放在工具入参：标签由接入方配置的 MCP 客户端声明，调用工具的模型改不了。不带这个头时只能检索到不限标签的切片。
- 工具调用读的是当前请求的主体与标签（传输上下文 `McpTransportContext`，由 `contextExtractor` 从过滤器写入的请求属性取），不随 MCP 会话固定；工具的执行线程不一定是请求线程，所以在工具里按主体重新绑定 `PlatformContext`。
- MCP Inspector 2.13.0 遇到 401 时会去探测 OAuth 元数据（`/.well-known/oauth-protected-resource`，404），之后一直等待直到超时，不会把 401 报出来。平台的 MCP Server 只接受 API Key，不提供 OAuth；接入方用 Inspector 调试时先确认 Key 有效。
- SDK 0.17.2 的服务端会话保存在进程内（`sessions`），只在客户端发 DELETE 或推送失败时移除，没有空闲过期：knowledge 多副本时，同一 MCP 会话的请求要路由到同一个副本（按 `Mcp-Session-Id` 粘滞），否则得到 404 由客户端重新初始化；不发 DELETE 的客户端会让会话一直累积。登记为 D82。
- SDK 0.17.2 的客户端在服务重启后（服务端返回 404）只在后台重新初始化，后台没有调用方的 Reactor Context，平台的凭据到不了请求头，之后的调用都失败（`McpToolsIT` 在原端口重启服务实测）。平台的 `tools.mcp.McpSession` 在调用因会话丢失失败时用本次的凭据重建客户端并重试一次（这几种失败下服务端都没有执行这次调用），登记事项 D53 的 A-F8 因此关闭。
