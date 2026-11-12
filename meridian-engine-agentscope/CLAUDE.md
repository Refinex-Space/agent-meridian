# meridian-engine-agentscope

内核适配层的 AgentScope 2.0.4 实现，唯一依赖 AgentScope 的生产模块：智能体装配与实例缓存、系统提示词分层、执行一轮、事件映射、带租约校验的状态存储、平台中间件、平台工具（文件、联网、知识检索、MCP、`bash`、`spawn_subagent`）、子智能体执行、两个档位的工作区与技能仓库、会话沙箱驱动与每轮注入。

## 命令

- 编译与单测：`./mvnw -pl meridian-engine-agentscope -am test`
- 装配、映射与场景测试在 `meridian-engine-agentscope-test`（它依赖本模块，测试放这里会形成循环）：`./mvnw -pl meridian-engine-agentscope-test -am test`
- kind 集成测试（`*KindIT`，标签 `kind`，在 engine-agentscope-test；先按根 `CLAUDE.md` 建集群并 `scripts/dev/deploy-sandbox.sh`）：`./mvnw -Pkind -pl meridian-engine-agentscope-test -am verify`
- 读 2.0.4 源码：`./mvnw -pl meridian-engine-agentscope -am compile dependency:sources -DincludeGroupIds=io.agentscope`，再 `unzip -p ~/.m2/repository/io/agentscope/<构件>/2.0.4/<构件>-2.0.4-sources.jar <类路径>.java`

## 包地图

- `turn.AgentScopeEngine`：`AgentEngine` 的实现，执行一轮的唯一入口。`runTurn` 取配置、租用实例、`begin`（身份映射、`clearStateCache`、带标记的权限安装）、`streamEvents`、`TurnEventMapper`，终态前归还资源；`TurnInputTranslator`、`PermissionTranslator` 做输入与权限翻译。
- `events.TurnEventMapper`：事件映射与增量拼装，`complete()` 给出唯一终态，静态的 `fail(Throwable)` 把错误翻译为 `session.error` 加终态；`AgentEventDisposition`（31 个事件类型）与 `StopReasonMapping`（`GenerateReason`）是穷举的 switch。
- `state.FencedAgentStateStore`：`AgentStateStore` 的实现，写入经 `LeaseRegistry` 取租约、经 `AgentStateRepository` 端口在同一事务中校验。
- `middleware`：`PlatformMiddlewareProvider` 交给装配器的平台中间件（参数：租约、收件箱、是否开启批次 checkpoint、每轮拉起子智能体的上限；子智能体不含 steer）；`PlatformStopRequest` 是平台中间件请求停止并记录原因的入口；`RecoveryNoteMiddleware`、`BatchCheckpointMiddleware`、`InterruptCheckpointMiddleware` 从 `RuntimeContext` 读取引擎放入的 `RecoveryNoteSlot`、`BatchCheckpoint`、`PendingInterrupt`；`SteerMiddleware` 经 `InboxPort` 取 steer（T08，ADR-0031）。
- `turn.AgentScopeEngine` 另有 `retryToolCall`（恢复确认的重试，直接调 Toolkit 中的工具，原幂等键放在 `ToolRetryContext`）与 `reservedToolNames`。
- `assembly.AgentAssembler`：`HarnessAgent.Builder` 的全部配置都在 `configure`，按「引擎装配」表逐段对应；`AgentInstanceCache` 对外 `acquire`/`acquireSubagent`/`release`；`AssembledAgent.reservedToolNames()` 供自定义工具校验；`CompactionPrompt` 是平台的摘要提示词。
- 提示词（T10b，ADR-0035）：`assembly.PlatformPrompt`（英文的平台基础提示词，`compose(租户, 智能体)`）、`TenantPromptProvider`（装配时经 `AgentConfigPort.tenantPrompt` 读一次，要求有 `PlatformContext`）、`PlatformContextMessage`（`AgentScopeEngine` 在 `NewMessages` 轮次的用户消息之前注入当前时间）。
- `tools`（T10a，ADR-0034）：`PlatformTool` 是所有平台工具的基类（`matchRule`、`generateSuggestions`、账本与幂等键、文件系统绑定、入参 schema 按键排序），`RuleSyntax` 是规则内容语法，`WorkspacePaths` 是路径规范化与路径 glob；六个文件工具与 `FileToolSupport`（先读后改、`grep_files` 按拒绝规则过滤）；`PlatformToolProvider` 按工具名登记工厂（工厂可以读 `AgentProfile`），`withMcp` 登记 MCP 连接器。
- `tools`（T10b，ADR-0035）：`WebFetchTool`、`WebSearchTool`、`KnowledgeSearchTool`（经端口，结果包来源标签，端口失败是错误结果）；`SourceTags` 是来源标签；`tools.mcp`：`McpConnector`（装配时建客户端、发现工具）、`McpPlatformTool`（`mcp__{server}__{tool}`）、`McpCredentials` 与 `McpCredentialInjector`（凭据经 Reactor Context 的 `McpTransportContext` 到请求头）。
- `fs`（T10a）：`PlatformWorkspace` 是 `none` 档的 `WorkspaceFilesystemProvider`（`skills()` 给出技能仓库提供者）；`MeridianBaseStore`（`BaseStore` → `WorkspaceStorePort`）、`WorkspaceNamespaces`（命名空间取自 `PlatformContext`）、`ReadOnlyFilesystem`、`ProtectedContent`（受保护路径的内容）、`MeridianSkillRepository`（继承 `WorkspaceSkillRepository`）。
- T12a（ADR-0037）：`tools.BashTool`（`factory` 只在 sandbox 档创建；`checkPermissions` 补拒绝与询问的任一段命中、多条放行规则共同覆盖；`timeout -k 5 N bash -c`；30000 字符保留头尾；经 `ToolLedgerPort.recordSandboxCommand` 审计）与 `ShellCommands`（引号外分段，命令替换等为 `Opaque`）；`fs.SandboxWorkspaceFilesystem`（平台路径 ↔ `/workspace`、`glob` 过滤、无本次沙箱绑定即拒绝），装配器为 sandbox 档以同一组路由重建 `RoutedSandboxFilesystem` 经 `PlatformTool.attach(owner, filesystem)` 交给工具；`sandbox.TurnSandboxes`（经 `SandboxStatePort.find` 与 `SandboxAttachment` 取沙箱，`filesystemSpec(settings)` 用 `PlatformManagedSandboxClient`）；`SkillProjection`（摘要与 tar）；`AgentScopeEngine` 的第 6 个构造参数 `TurnSandboxes`，`begin` 放入 `SandboxContext`，`retryToolCall` 另放 `SandboxAcquireResult`。
- 子智能体（T13，ADR-0040）：`tools.SpawnSubagentTool`（不经 `with` 登记，`PlatformToolProvider` 在其他工具之后为声明了子智能体的主智能体加入，副作用取子智能体工具在本实例中的最高等级；覆盖 `PlatformTool.executeAsync` 响应式执行）只校验入参与写结果文本；`turn.SubagentRunner`（`AgentScopeEngine` 第 7 个构造参数）执行：`ThreadRegistryPort` 建子线程、`attachChildThread`、空白构建的子 `RuntimeContext`、预合成权限（缺失时失败关闭）、新的 `TurnEventMapper` 经 `EventSinkPort` 写子线程事件、终态换成 `thread_status_idle`、`takeUntilOther` 超时；`SubagentSlot` 是每轮放进主线程 `RuntimeContext` 的入口与子线程登记（`interrupt` 先中断子线程）；`SubagentOutcome`；`middleware.SubagentQuota` 由 `PolicyMiddleware` 按批内顺序分配名额。`InterruptRelay`（受理前的中断在受理后补发）与 `ExecutionSlot`（准备中被取消也恰好清理一次）由主线程与子线程共用。
- `sandbox`（T11，ADR-0036）：`KubernetesSandboxDriver` 实现 `SandboxDriver`（经 `KubernetesSandboxClient` 认领 `sandbox-<规格>` 预热池、`resume` 先查 claim 与 `Sandbox` 是否存在、`restore` 由 harness 的快照分支展开、`attach(state)` 给出本节点缓存的 `SingleStartSandbox` 供 `SandboxContext.externalSandbox`）；`SingleStartSandbox`（`start()` 只执行一次，失败可重试，委托 `SandboxFileTransfer`）；`PlatformSnapshotClient`（`RemoteSnapshotClient` → `SnapshotStorePort`，快照 ID 即对象键）；`SandboxDriverSettings`（命名空间、预热池前缀、连接方式 `PortForward`/`Direct`/`Gateway`、超时）。
- `assembly.ToolPolicy`：装配自检（非 `ToolBase` 只允许 `load_skill_through_path`）、策略拒绝规则（`wait_async_results`、工具集外的工具，`AssembledAgent.policyDenyRules()`，引擎每轮并入权限）、启动自检的探测对象；`AgentAssembler.selfCheck`。
- `model`（T09a，ADR-0032）：`RoutedModel`（`doStream`：resolve → 限流 → 预扣 → 带重试的调用 → `usingWhen` 结算；降级在 `ProviderFailure` 之上决定）、`RoutedModelFactory`（装配时 `describe` 取上下文窗口，智能体没配备用模型时取目录的）、`ProviderClients`（按账号加模型缓存不含凭据的客户端）、`OpenAiCompatibleClientFactory`、`AnthropicClientFactory` 与 `GeminiClientFactory`（T15a，ADR-0046：平台适配器 `AnthropicMessagesModel`、`GeminiContentModel` 复用 2.0.4 的 formatter 与解析器）、`ProviderHttpTransport`（记录 `Retry-After`）、`ProviderErrors`（可重试、拒绝、非供应商错误）、`TokenEstimator`、`CompactionWatch`（压缩期间的 Reactor Context 标记）。`RoutedModelInvoker`（T09b，ADR-0033）是 `ModelInvoker`：每次调用一个只含主模型的 `RoutedModel`，`ChunkTranslator` 把 2.0.4 的流式工具调用（首块带名字，后续片段名为 `__fragment__`、不带序号）归并到序号上。

## 中间件顺序（P13，T04 测得）

`order()` 数值大的在外层；`MiddlewareChainTest` 断言链路与 span 父子关系。装配后的实际链路（外层在前）：

`GracefulShutdownMiddleware`（AgentScope 构造时固定放在最外层）→ `PlatformContextMiddleware`(300) → `PlatformTracingMiddleware`(200，委托 `OtelTracingMiddleware`) → `LeaseGuardMiddleware`(100) → `RecoveryNoteMiddleware`(90) → `InterruptCheckpointMiddleware`(88) → `SteerMiddleware`(85) → `BatchCheckpointMiddleware`(80) → `PolicyMiddleware`(70) → `ToolSpanMiddleware`(50) → `CompactionGuardMiddleware`(20，外层，按智能体的压缩预留创建) → `ContextCompositionMiddleware`(10，`onModelCall`) → harness 自带：`AtPathExpansionMiddleware`、`CompactionMiddleware`、`ToolResultEvictionMiddleware`、`InboxMiddleware`、`HarnessSkillMiddleware`（都是 1，按注册顺序）→ `CompactionGuardMiddleware.Inner`(0)。P13 通过（T09a），报告 `docs/poc/P13-中间件顺序.md`。

span 层级：`turn` → `invoke_agent` → `chat {model}` 与 `execute_tool`（批次）→ `execute_tool {name}`（每个调用）。后续中间件选 `order()` 时以此为准：要在压缩之前生效的大于 1，要夹在压缩之内的小于 1；在推理前往上下文注入消息的必须大于 80，注入的内容才进入该次 checkpoint（`SteerMiddleware` 为 85）。

## 新增平台工具（T10b、T12a 照此做；`spawn_subagent` 由提供者内建）

1. 在 `tools` 包写一个 `PlatformTool` 子类：构造时给 `Spec`（名字取自 `PlatformToolNames`、英文描述写清用途、何时使用、参数、返回格式、限制；入参 JSON Schema；副作用等级；`RuleSyntax`；能否与同批调用并行），实现 `execute(ToolCall)`。
2. `execute` 在 `Blocking` 的虚拟线程上运行，可以阻塞（不要用有上限的 `boundedElastic`，P7）；文件与命令操作用 `call.filesystem()` 并传入 `call.runtimeContext()`；可预期的问题抛 `ToolInputException`（英文说明，交给模型修正）；`non_idempotent` 工具把 `call.idempotencyKey()` 传给下游。不要自己记账。
3. 在 `PlatformToolProvider` 登记工厂（runtime 的 `PlatformToolsAutoConfiguration` 经 `with(name, factory)` 加入；要读智能体配置时用 `(services, profile) -> …`）；输出超过 50000 字符时自己截断并提示如何继续（工具结果卸载阈值 80000）；外部内容先截断再用 `SourceTags.wrap` 包标签。
4. 测试：`turn.EngineHarness.withPlatform(...)` 加 `ScriptedModel` 的场景测试（规则、账本、结果）；ArchUnit 规则 13 要求生产代码中的 `ToolBase` 子类都继承 `PlatformTool`。

规则内容语法：路径 glob（相对工作区根，`**` 匹配零或多级目录，`dir/**` 也匹配 `dir`）、命令前缀（`git status:*`）、域名（`domain:example.com`，含子域名）、资源 ID（列表中每个值都要被覆盖）；规则内容为空时匹配全部调用。

## 提示词分层与来源标签（ADR-0035）

- 系统提示词：`sysPrompt` = 平台基础提示词 → `# Organization instructions`（租户）→ `# Agent instructions`（智能体），空的层不出现；`HarnessSkillMiddleware` 在每次推理把技能清单追加在最后。`disableWorkspaceContext()` 关掉了 2.0.4 往系统提示词追加日期、本机路径与 `AGENTS.md` 的中间件。基础提示词里不能出现随租户、会话、时间变化的内容；`PromptLayeringScenarioTest` 断言两个会话的前缀相同。
- 动态内容只以消息注入：`<platform_context>\nCurrent time: …Z (UTC)\n</platform_context>`，元数据 `meridian_platform_context`，只在 `NewMessages` 轮次。
- 来源标签：`<external_content source="web_fetch|web_search|knowledge_search|mcp" 属性…>\n内容\n</external_content>`。内容中匹配 `(?i)<\s*/?\s*external_content` 的片段把 `<` 换成 `&lt;`；属性值 XML 转义、换行变空格。知识库每个切片一个标签，MCP 错误结果也包标签。
- MCP：先选传输再设定制器；用 `buildAsync()`（同步客户端丢 Reactor Context，凭据到不了请求头）；凭据每次调用前经 `SecretResolver` 解析，只放进本次调用的 Context，结果中的明文替换为 `***`；装配时连不上服务抛 `McpUnavailableException`（可重试），凭据不可用是终止错误。

## 不变量

- 装配必须满足「引擎装配」表：`maxRetries(1)`、不设 `fallbackModel`、`enablePendingToolRecovery(true)`、关闭全部内置工具与 transcript、`disableAtPathExpansion()`、工具执行超时 660 秒、`NoopMessageBus` 加 `NoopAsyncToolRegistry`、`enableAgentTracingLog(false)`、压缩 `triggerMessages(0)` 加平台摘要提示词；`AgentAssemblerTest` 逐项断言，改装配先改表。
- 所有模型调用（含压缩器）都经 `RoutedModel`；底层客户端每次调用只尝试一次（`ExecutionConfig.maxAttempts(1)`），重试只在 `RoutedModel` 针对首包之前的供应商错误；预扣与结算由 `usingWhen` 成对保证。
- 缓存键 `(agent_version_id, environment_id, environment_revision[, 子智能体名])`，容量 500，空闲 30 分钟；淘汰只退役，引用归零才关闭（ADR-0024）。`release` 由 `AgentScopeEngine` 在终态之前调用，每个凭据一次。
- 每轮恰好一个终态：正常结束按 `GenerateReason`；错误先 `session.error` 后终态；`AgentShuttingDownException` 只发 `rescheduled(node_shutdown)`；`RetryableTurnException` 发 `error(retryable)` 加 `rescheduled(retryable_error)`；`LeaseLostException` 不发任何事件、原样结束；取消订阅不发事件、不写状态（ADR-0026）。
- 每轮开始前与结束时（含异常、取消）都 `clearStateCache(userId, threadId)`；权限安装期间 `LeaseRegistry.markPermissionInstall` 为真，状态存储据此不更新 `content_epoch`（「租约与 fencing」）。
- 同一节点同一线程同时只有一次执行，`AgentScopeEngine` 登记执行，重复执行失败。
- 阻塞步骤（装配、权限安装时的状态读写）经 `Blocking` 在虚拟线程上执行，AgentScope 的调用也在该线程上订阅，`activateSlotForContext` 的状态读取因此不在 Reactor 线程上。

## 易错点

- 2.0.4 在 `onActing` 的事件流结束之后才把工具结果写进 `AgentState`（`notifyPostActingHook`）：批次 checkpoint 放在下一次推理之前保存，在 `onActing` 结束时保存会得到没有 tool_result 的状态（P3）。
- 推理输入是 `PreReasoningEvent` 复制出来的列表：往上下文注入消息要同时加进 `ReasoningInput.messages()` 与 `ctx.getAgentState().contextMutable()`，否则本次模型调用看不到或状态里存不下。
- 工具的 `Mono` 出错（例如账本写入被拒）时，2.0.4 的 `ToolExecutor` 把它转成错误结果，调用继续；不会让整轮失败。

- `maxRetries` 是含首次在内的总尝试次数，0 会抛异常（ADR-0023）。`RoutedModel` 调用底层模型时不能放大它；压缩器以空选项调用，底层客户端会取 `MODEL_DEFAULTS`（3 次），所以平台选项必须显式带 `maxAttempts(1)`。
- `CompactionMiddleware` 在调用下游之前就把失败的摘要写进 `AgentState`：内层守护必须先用 `CompactionWatch` 的副本恢复上下文再抛异常，否则出错时保存的状态丢了历史（P13 对照实验）。
- 2.0.4 的 `JdkHttpTransport` 在流式路径上丢弃响应头，`OpenAIException` 也不带头：`Retry-After` 只能由 `ProviderHttpTransport` 记到 Reactor Context 的 `ProviderResponseObserver` 上。
- 不要用 2.0.4 的 `AnthropicChatModel`、`GeminiChatModel`（ADR-0046）：凭据只能在构造时给，SDK 自带重试（Anthropic 2 次、Gemini 5 次）关不掉，流在 `boundedElastic` 上迭代。适配器里 SDK 的 `maxRetries(0)`/`attempts(1)`、在 `Blocking.scheduler()` 上订阅、SDK 异常转成带状态码的 `HttpTransportException`（`ProviderAdaptersIT` 守住）；Anthropic 的凭据是每次请求的 `x-api-key`，Gemini 的 SDK 用密钥覆盖请求头，只能按凭据指纹缓存客户端（5 分钟后关闭）。2.0.4 的 `GeminiMediaConverter` 会在本进程下载 URL 图片，适配器遇到 URL 媒体直接拒绝。
- 中间件经 `CustomEvent` 向事件流发平台事件，`TurnEventMapper` 按名字映射（`PLATFORM_CUSTOM`）；上下文构成挂在 `ModelCallStartEvent` 的元数据上，不经端口写库。
- 只设 `messageBus` 不够：不显式设置 `asyncToolRegistry` 时，harness 用工作区实现，`InboxMiddleware` 每次推理前都会读工作区。
- `Toolkit.copy()` 不转移 MCP 客户端所有权：需要关闭的资源放进 `ToolBundle.resources`。
- `disableDynamicSkills()` 让技能清单在构建时冻结；技能仓库必须按装配时的智能体版本解析。
- `CompositeFilesystem` 会剥离目录路由前缀，单文件路由不剥离；每个目录前缀要有自己的后端视图。
- 装配时 `workspace(...)` 设为提供者给的本机目录（D05）：不设时 harness 用 `${user.dir}/.agentscope/workspace` 并从中回退读取 `AGENTS.md` 等；平台不在其中写入，P4 测试断言它不存在。
- `MarketplaceStager` 每次构建系统提示词都把非 `WorkspaceSkillRepository` 仓库的技能资源写到本机 `.skills-cache/`：技能仓库必须继承 `WorkspaceSkillRepository`。
- `wait_async_results` 在 2.0.4 中是 `ReflectiveFunctionTool`（`ToolBase`），会进权限引擎；`onActing` 中间件自己合成的事件不会被发布（2.0.4 只发布 `actingStream` 内部的事件），要拒绝工具就用权限规则（`ToolPolicy.denyRules`）。策略拒绝规则只并入非平凡的策略，否则平凡上下文的"一律放行"会变成"都要确认"。
- 工具结果的元数据随 `AgentState` 持久化、不发给模型：先读后改的读取状态存在 `meridian_file` 里；超过 16 KB 的结果在事件中只有对象存储引用。
- `RemoteFilesystem.write` 只在键不存在时成功，覆盖要用 `uploadFiles`；`CompositeFilesystem` 对单文件路由查存在性时传 `//AGENTS.md`、列目录时大小报 0。
- 2.0.4 从不赋值 `GenerateReason.STRUCTURED_OUTPUT`，结构化输出完成时为 `MODEL_STOP`。
- 2.0.4 的块 ID 每次模型调用都从 `text` 重新编号，平台块 ID 是 `{replyId}:{blockId}`；并行工具调用交错时同一 ID 会多次 `TOOL_CALL_START/END`，入参在 `MODEL_CALL_END` 才完整（ADR-0026）。
- 一批工具的 `TOOL_RESULT_END` 在整批执行完后才逐个发出；`AgentScopeEngine` 之外不要据此计时单个工具。
- 审批批准时 `ConfirmResult.toolCall` 必须带 `content`（入参 JSON 原文）：`ToolExecutor` 按它做 schema 校验，只给 `input` 会报"argument content is null"。
- `interrupt(userId, sessionId)` 在调用被受理（进入 `runningCalls`）之前是空操作，返回值也拿不到；引擎自己登记执行并在受理后补发。
- `GracefulShutdownManager` 是 JVM 单例，`performGracefulShutdown` 之后拒绝所有新调用；测试结束必须 `resetForTesting()`。`AgentScopeEngine` 构造时把它的超时设为 45 秒。
- `OtelTracingMiddleware` 的 span 在它自己的方法里创建，补属性要在内层从 Reactor Context 取 span（`PlatformTracingMiddleware`）；`ContextPropagationOperator` 来自 AgentScope 带来的 `opentelemetry-reactor-3.1`。
- 自定义工具挂起时 2.0.4 发出 `RUNNING` 的 `TOOL_RESULT_END`，映射器跳过它；`agent.custom_tool_use` 以 `REQUIRE_EXTERNAL_EXECUTION` 中的调用为准。
- 权限（2.0.4 已核实，P15）：显式 ask 规则在 `DONT_ASK` 下仍返回 ask，只有最后一步的默认 ask 变成 deny，所以无人审批的执行要把 ask 改写为 deny；`ConfirmResult.rules` 只加进本次调用缓存的 `PermissionEngine`，不写回状态，"记住此决定"由平台保存。
- 权限上下文"平凡"（`DEFAULT`、没有规则、没有工作目录）时 2.0.4 不走 `PermissionEngine`、一律放行；平台每轮注入安全规则，上下文永远不平凡，`SchemaOnlyTool`（自定义工具）也是 `ToolBase`，同样受规则约束。
- 一批工具中只要有一个 ask，整批都不执行（含已放行与自定义工具），确认之后整批一起执行；全部被拒绝（`ALL_TOOLS_DENIED`）时默认继续推理。
- 在 `onReasoning` 中以 `InterruptedException` 结束等同于用户中断（`handleInterrupt`：补齐悬空调用、保存状态、`INTERRUPTED`）；不调下游只发 `RequestStopEvent` 时没有模型输出，推理返回空，不能这样停。
- 工具直接返回的 `ToolResultBlock` 状态默认为 `RUNNING`（由 `ToolExecutor` 定稿），直接调用工具时不能用 `SUCCESS` 判断成功。
- `Map.of` 的迭代顺序每个 JVM 随机：工具定义（入参 schema）由 `PlatformTool` 规整为按键排序，否则不同节点的请求前缀不同、命中不了提示词缓存。
- 沙箱（2.0.4 已核实）：`KubernetesSandboxClient` 合并单次选项时非空字段一律覆盖默认值，命名空间、工作区、文件接口目录、端口都有非空缺省，单次选项必须完整设置；超时只取默认选项。`getSandbox` 在 claim 不存在时会监听等满 30 秒，所以 `resume` 先用 fabric8 查 claim。`KubernetesSandbox.shutdown()` 删除 claim 失败只打日志，驱动自己删 claim。`doExec` 不把超时传给执行服务，非零退出码抛 `ExecException`。三种连接方式都经沙箱命名空间中的 `sandbox-router-svc`。
- `bash` 的命令前缀规则只认得简单命令：`$'…'`、复合命令关键字、以变量为命令名、`eval`/`source`/`sh -c` 都归为不可分析（ADR-0041）；改 `ShellCommands` 时先在 `ShellCommandsTest` 加真实 bash 的反例。驱动在沙箱里跑的 Python 辅助脚本用 `python3 -I`，工作目录是智能体可写的 `/workspace`。
- 2.0.4 在执行工具前按入参 schema 校验参数（含 `enum`），不合法的调用直接得到 "Parameter validation failed" 错误结果，不进 `execute`。
- sandbox 档（2.0.4 已核实，ADR-0037）：`RuntimeContext` 没有 `SandboxContext` 时 harness 用装配时的默认上下文自建沙箱，所以 sandbox 档每轮必须注入外部沙箱；`SandboxBackedFilesystem` 没有本次绑定时退回实例上最后绑定的沙箱（跨会话），工具必须经 `SandboxWorkspaceFilesystem`；`BaseSandboxFilesystem` 把路径原样拼进 shell，`CompositeFilesystem` 列根目录传 `/`；`RoutedSandboxFilesystem.execute` 不经路由。
- 子智能体（2.0.4 已核实，ADR-0040）：`RuntimeContext.builder(parent)` 复制 `agentState`、`toolRequestConfig` 与全部类型化属性，`HarnessAgent` 只在上下文没有 `AbstractFilesystem` 时放本实例的，子线程上下文必须空白构建；在父线程的工具调用里 `streamEvents` 子智能体时，它自己的事件 sink 覆盖继承的键，事件不进父线程的流；子线程的终态不能写成 `session.status_*`（带线程的状态事件会推进会话状态）；取消子智能体的订阅后 AgentScope 把 `CancellationException` 交给 `onErrorDropped`，日志有一条 ERROR。
- 权限引擎对 allow、ask、deny 都调同一个 `matchRule`；工具自检（`checkPermissions`）在拒绝、询问规则之后、放行规则之前，返回的询问理由带 `safety` 才不再查放行规则，`DONT_ASK` 下不会自动变成拒绝。

## 对外接口

- 实现 `meridian-engine-spi` 的 `AgentEngine`（`turn.AgentScopeEngine`，构造时注入 `Clock`、可选的 `TurnSandboxes` 与 `SubagentRunner`）与 `SandboxDriver`（`sandbox.KubernetesSandboxDriver`，依赖 `SnapshotStorePort`，runtime 的 `config.SandboxConfiguration` 装配）；依赖端口 `AgentConfigPort`（含 `skill`、`tenantPrompt`）、`LeaseRegistry`、`AgentStateRepository`、`BlobStorePort`、`InboxPort`、`ToolLedgerPort`、`WorkspaceStorePort`、`WebFetchPort`、`WebSearchPort`、`KnowledgeSearchPort`、`SecretResolver`（MCP）、`SandboxStatePort`（T12a）、`ThreadRegistryPort` 与 `EventSinkPort`（T13，子线程）。
- runtime 只能在 `config` 包引用本模块的装配入口：`AgentAssembler`、`AgentInstanceCache`、`AgentScopeEngine`、`FencedAgentStateStore`、`PlatformMiddlewareProvider`、`RoutedModelFactory`、`RoutedModelInvoker`、`ProviderClients`、`ProviderHttpTransport`、`OpenAiCompatibleClientFactory`、`PlatformToolProvider`、`PlatformTool.ToolServices`、`PlatformWorkspace`、`ToolPolicy`、`TenantPromptProvider`、三个联网与知识检索工具、`McpConnector` 与提供者接口；T12a 起另有 `BashTool`、`KubernetesSandboxDriver`、`SandboxDriverSettings`、`TurnSandboxes`、`SandboxAttachment`；T13 起另有 `SubagentRunner`。依赖端口另有 `ModelGateway`（T09a）。
- 生产实现的提供者：模型工厂（T09a，`ModelFactoryAutoConfiguration`）；工具、两个档位的文件系统、技能仓库（`PlatformToolsAutoConfiguration`，`PlatformWorkspace` 的第 4 个构造参数给出 sandbox 档的规范）；中间件 T04 起（T19 继续加入）。`ModelInvoker` 由 `RoutedModelInvoker` 实现（T09b），runtime 在 `config` 装配。
