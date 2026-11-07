# meridian-engine-agentscope

内核适配层的 AgentScope 2.0.4 实现，唯一依赖 AgentScope 的生产模块。目前有 `assembly`（智能体装配与实例缓存）；执行一轮、事件映射、`FencedAgentStateStore` 从 T04 起加入。

## 命令

- 编译与单测：`./mvnw -pl meridian-engine-agentscope -am test`
- 装配测试与场景测试在 `meridian-engine-agentscope-test`（它依赖本模块，测试放这里会形成循环）：`./mvnw -pl meridian-engine-agentscope-test -am test`
- 读 2.0.4 源码：`./mvnw -pl meridian-engine-agentscope -am compile dependency:sources -DincludeGroupIds=io.agentscope`，再 `unzip -p ~/.m2/repository/io/agentscope/<构件>/2.0.4/<构件>-2.0.4-sources.jar <类路径>.java`

## 包地图

- `assembly.AgentAssembler`：`HarnessAgent.Builder` 的全部配置都在 `configure`，按「引擎装配」表逐段对应；未实现的组件经 `ModelFactory`、`MiddlewareProvider`、`ToolProvider`、`WorkspaceFilesystemProvider`、`SkillRepositoryProvider` 与注入的 `AgentStateStore` 获得。
- `assembly.AgentProfile`：主智能体或某个子智能体加环境，装配器与提供者都按它工作；`key()` 即缓存键 `AssemblyKey`。
- `assembly.AgentInstanceCache`：对外的 `acquire`/`acquireSubagent`/`release`；内部是泛型的 `RefCountedCache`（Caffeine 管容量与空闲，条目管引用计数与退役）。
- `assembly.AssembledAgent`：`HarnessAgent` 加平台资源；`reservedToolNames()` 供自定义工具校验。

## 不变量

- 装配必须满足「引擎装配」表（v1.2）：`maxRetries(1)`、不设 `fallbackModel`、`enablePendingToolRecovery(true)`、关闭全部内置工具与 transcript、`NoopMessageBus` 加 `NoopAsyncToolRegistry`、`enableAgentTracingLog(false)`；`AgentAssemblerTest` 逐项断言，改装配先改表。
- 缓存键 `(agent_version_id, environment_id, environment_revision[, 子智能体名])`，容量 500，空闲 30 分钟；淘汰只退役，引用归零才关闭，关闭在执行器上进行（ADR-0024）。
- `release` 必须在 `finally` 中调用，同一个 `AgentHandle` 只能归还一次。

## 易错点

- `maxRetries` 是含首次在内的总尝试次数，0 会抛异常；它经 `GenerateOptions.executionConfig.maxAttempts` 传给模型，重试发生在供应商模型类里（ADR-0023）。`RoutedModel` 调用底层模型时不能放大它。
- 只设 `messageBus` 不够：不显式设置 `asyncToolRegistry` 时，harness 用工作区实现，`InboxMiddleware` 每次推理前都会读工作区。
- `Toolkit.copy()` 不转移 MCP 客户端所有权：经 `Builder.toolkit(...)` 传入的工具包上注册的 MCP 客户端，`HarnessAgent.close()` 不关；需要关闭的资源放进 `ToolBundle.resources`。
- `disableDynamicSkills()` 让技能清单在构建时用 `RuntimeContext.empty()` 冻结；技能仓库必须按装配时的智能体版本解析。
- `CompositeFilesystem` 会剥离目录路由前缀（`skills/a.md` 到后端变成 `/a.md`），单文件路由（`/AGENTS.md`）不剥离；每个目录前缀要有自己的后端视图。
- `HarnessAgent` 不设 `workspace(...)` 时本机工作区根是 `${user.dir}/.agentscope/workspace`，harness 会读其中的 `skills/`；不禁用 transcript 时会往这里写会话记录（登记事项 D05，T10a 处理）。
- 2.0.4 从不赋值 `GenerateReason.STRUCTURED_OUTPUT`，结构化输出完成时为 `MODEL_STOP`；`generate_response` 的入参是 `{"response": {...}}`。
- `ReActAgent.Builder` 按 `order()` 降序排序中间件（稳定排序）；装配器传入的平台中间件先于 harness 自带的中间件注册。

## 对外接口

- 依赖 `meridian-engine-spi` 的 spec 类型；runtime 只能在 `config` 包引用本模块的装配入口（`AgentAssembler`、`AgentInstanceCache` 与提供者接口）。
- 生产实现的提供者：模型工厂 T09a，中间件 T04 起，工具 T10a、T10b、T12a、T13，文件系统 T10a、T12a，技能仓库 T10a，状态存储 T04/T05。
