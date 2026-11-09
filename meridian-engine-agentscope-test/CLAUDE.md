# meridian-engine-agentscope-test

内核测试夹具：脚本化假模型 `ScriptedModel`、端口的内存实现、装配用的测试替身与测试工具，以及 `meridian-engine-agentscope` 的装配测试、缓存测试与场景测试。只以 test 作用域被依赖，不进入生产构件。

## 命令

- 编译与单测（含 engine-agentscope 的装配与场景测试）：`./mvnw -pl meridian-engine-agentscope-test -am test`
- 单个测试：`./mvnw -pl meridian-engine-agentscope-test -am test -Dtest=<类名> -Dsurefire.failIfNoSpecifiedTests=false`

## 包地图

- `testkit.model`：`ScriptedModel`（`respond`、`respondTo`、`failBeforeFirstChunk`、`failAfterChunks`）、`ScriptedResponse`（文本与思考按大小分块、工具调用入参分两块、`structuredOutput`、`rawChunk`、`usage`、`thenFail`）、`RecordedRequest`（`toolNames`、`lastUserText`、`toolResults`、`systemPrompt`、`options`）。
- `testkit.model`（T09a）：`InMemoryModelGateway`（登记路由、一级预算、限流超时开关，记录预扣、结算、释放与预检）、`StaticProviderClientFactory`（按供应商侧模型名返回脚本作为底层客户端），配合 `RoutedModelFactory` 测 `RoutedModel` 与压缩守护。
- `testkit.port`：`InMemoryLeaseRegistry`、`InMemoryAgentStateRepository`（`activate`/`deactivate` 模拟数据库中的租约条件）、`InMemoryEventSink`、`InMemoryBlobStore`、`InMemoryAgentConfig`（含技能与租户提示词 `tenantPrompt(tenantId, text)`）、`InMemoryInbox`、`InMemoryToolLedger`、`InMemoryThreadRegistry`（把 `session.thread_*` 写进给定的 `InMemoryEventSink`，T13）、`InMemoryWorkspaceStore`（与数据库实现相同的版本语义）、`InMemorySnapshotStore`（T11）、`InMemorySandboxStates`（T12a）；`InMemoryToolLedger.sandboxCommands()` 是记下的沙箱命令审计。
- `testkit.assembly`：`TestAssembly.builder(model)` 组装带替身的 `AgentAssembler`（默认没有租户层，`tenantPrompts(...)` 指定）；`TestSpecs.agent()` 构建 `ResolvedAgentSpec`（`mcpServer(...)` 挂 MCP 服务），`noneEnvironment()`/`sandboxEnvironment()`；`InMemoryWorkspaceFilesystems`（受保护路径各一个内存文件系统，`RecordingBaseStore` 记录访问）、`FakeSandboxFilesystemSpec`（能装配、不能建沙箱）。
- `testkit.tool.RecordingTool`：继承 `ToolBase` 的测试工具，`echo(name)` 返回 `name:text`。`PathRuleTool`：实现 `matchRule` 的路径工具（规则内容是路径 glob，`**` 匹配零或多级目录），`withSafetyAsk` 对指定路径返回安全类 ask（T08，P6、P15）。
- 测试：`assembly.AgentAssemblerTest`（装配表逐行）、`assembly.AgentInstanceCacheTest`、`testkit.model.ScriptedModelScenarioTest`、`state.FencedAgentStateStoreTest`、`events.*`（穷举映射与映射器单测）、`turn.*`（引擎场景、下线、中间件链与 span、输入翻译）；包名与被测类相同，可读包内常量。
- `turn.EngineHarness.withPlatform(...)`（T10a）：生产的 `PlatformToolProvider`、`PlatformWorkspace` 与技能仓库，端口用内存实现（`harness.workspace`、`harness.ledger`）；可经 `PlatformToolProvider.with` 登记测试用的平台工具。文件工具、技能、工具策略的场景测试：`turn.FileToolScenarioTest`、`SkillScenarioTest`、`PlatformToolLedgerTest`、`PolicyMiddlewareScenarioTest`。
- T10b：`turn.EngineHarness` 的装配读租户提示词（`harness.configs.tenantPrompt`），时钟固定为 `EngineHarness.NOW`；直接租用实例用 `harness.acquire()`（带 `PlatformContext`）；`withMcp(model, spec, connector)` 登记 MCP 连接器。场景测试：`PromptLayeringScenarioTest`、`ExternalContentToolScenarioTest`、`tools.SourceTagsTest`；集成测试 `turn.McpToolsIT`：`McpTestServer` 是 Boot 4.1 Spring MVC 应用，以 Servlet 注册 MCP SDK 的 Streamable HTTP 与 SSE 传输（P8 预验证）。
- `testkit.sandbox.FakeSandbox`（T12a）：脚本化的会话沙箱，记录命令、按 `respond(...)` 给结果，非零退出码抛 `ExecException`，`stop`/`close` 抛异常（外部沙箱不应被 harness 停止）。`turn.EngineHarness.withSandbox(model, spec, sandbox, 工具…)`：`sandbox` 档、生产的平台工具（含 `bash`）与 `TurnSandboxes`；场景测试 `BashToolScenarioTest`（P6 bash 规则、截断、审计、两线程共享沙箱、路径翻译、大结果卸载）、`tools.ShellCommandsTest`、`fs.SandboxWorkspaceFilesystemTest`、`sandbox.SkillProjectionTest`；`KubernetesSandboxDriverKindIT` 另验证技能投影与产物清单。
- `sandbox`（T11）：`SingleStartSandboxTest`（并发 `start()` 只执行一次）、`PlatformSnapshotClientTest`；kind 集成测试 `KubernetesSandboxDriverKindIT`（认领、执行、上传下载、快照、删除、恢复、另一驱动实例重连）与 `SandboxPoolMeasurementKindIT`（P5 测量，结果写 `target/p5/p5-results.json`），标签 `kind`，`-Pkind` 才运行，kubeconfig 上下文 `kind-meridian`。
- T13：`turn.EngineHarness.withSubagents(model, spec, 模型 ID→模型, 每轮上限, 平台工具, 沙箱)`：生产的 `spawn_subagent`、主智能体与子智能体各自的中间件集合、`SubagentRunner`；子线程的事件在 `harness.events`，子线程在 `harness.threads`；`claim(..., subagentPolicies, ...)` 给出预合成的子线程策略。子智能体的工具必须是平台工具名，测试替身用平台工具名经 `withSubagents` 的工具映射登记。场景测试 `turn.SubagentScenarioTest`（含 P12）。`InMemoryLeaseRegistry` 按被写入的线程记录权限安装标记（与 runtime 相同）。
- `turn.EngineHarness`（测试源码）：真实装配 + 平台中间件（按智能体配置创建，含压缩守护外层）+ `FencedAgentStateStore` + 内存端口 + `AgentScopeEngine`；接受任意 `Model`（例如 `RoutedModel`）；`claim(...)` 模拟 Worker 认领，`run(...)` 断言恰好一个终态且在最后；`createFiltered` 去掉某些平台中间件做对照实验；`states.jsonOf(userId, threadId)` 读状态原文。

## 不变量

- 可以依赖 common、engine-spi、engine-agentscope 与 AgentScope（ADR-0025）；被其他模块依赖时只能是 test 作用域（根 POM 的 enforcer）。测试作用域另有 `spring-boot-starter-webmvc`（只给进程内 MCP 服务用），它带来 Logback，日志级别在 `src/test/resources/logback-test.xml`。
- 智能体行为测试只用 `ScriptedModel`，不依赖真实模型（架构原则第 10 条）。

## 易错点

- `ScriptedModel` 每次模型调用取走一步，脚本用完以 `IllegalStateException` 失败；结构化输出、工具结果回传都会多一次模型调用，脚本步数要算上。
- 超过 16 KB 的工具结果在事件中只有 `contentRef`，从 `harness.blobs` 读原文。
- 共享一个 `HarnessAgent` 的多个用例要用不同的线程 ID（`RuntimeContext.sessionId`），否则状态互相串。
- 对照实验用 `HarnessAgent.builder()` 直接构建时要 `disableTranscript()`，否则会在模块目录写 `.agentscope/`。
- 并发测试的线程池大小不能小于"装配函数等待到达的调用者数"，否则会卡到超时。
- 动用 `GracefulShutdownManager`（JVM 单例）的用例放在独立的类里，`@AfterEach` 调 `resetForTesting()`，否则同一 JVM 中后面的用例都会以 `node_shutdown` 结束。
- span 断言用 `OpenTelemetryExtension`（`opentelemetry-sdk-testing`，test 作用域）；它注册全局 `GlobalOpenTelemetry`，平台与 AgentScope 的 span 都取全局 tracer。
- 实例缓存的移除回调异步执行：`instances.close()` 之后资源（MCP 客户端）稍晚才关闭，断言要等待。
- 每个新消息轮次多一条 `<platform_context>` 消息：断言上下文构成、压缩前后消息数时要算上它。
- 要在执行中途中断、取消或下线，用阻塞到测试放行的工具（`AgentScopeEngineScenarioTest.LatchTool`）卡住执行；`ScriptedModel` 的回应不能延迟。

## 对外接口

T04 起的引擎场景测试、runtime 的 `config` 包集成测试（P1 的 `AgentScopeRuntimeBaselineIT`）复用这里的夹具。
