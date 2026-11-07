# ADR-0025 内核测试夹具可依赖 common 与 engine-spi

| 项 | 内容 |
|---|---|
| 状态 | 已采纳 |
| 日期 | 2026-11-07 |
| 决策人 | T03 执行会话提议，用户于 2026-11-07 采纳 |
| 影响的架构设计章节 | 「模块依赖规则」中 `meridian-engine-agentscope-test` 一行（"没列出的一律禁止"） |

## 背景

架构设计「模块依赖规则」规定 `meridian-engine-agentscope-test` 只能依赖 `engine-agentscope` 与 AgentScope，`meridian-arch-tests` 的 `MeridianModule` 据此只放行 `ENGINE_AGENTSCOPE`。

T03 的任务要求这个模块提供"本任务及 T04 需要的端口的内存实现"与装配用的测试替身。端口接口（`LeaseRegistry`、`AgentStateRepository`、`BlobStorePort` 等）、配置类型（`ResolvedAgentSpec`、`EnvironmentSpec`）都在 `meridian-engine-spi`，ID 生成与平台异常在 `meridian-common`。实现这些夹具就要直接引用 spi 与 common 的类，ArchUnit 规则 3 会判为违规。两份文档在这里冲突：

- Maven 层面没有问题：`engine-agentscope-test` 依赖 `engine-agentscope`，后者依赖 common 与 engine-spi；ADR-0022 的 enforcer 配置已经放行这两个传递依赖。
- 依赖方向不变：common、engine-spi 都在 `engine-agentscope` 之下，测试夹具引用它们不会产生反向或环形依赖。

修改「模块依赖规则」需要变更型 ADR（ADR-0022「影响」一节），所以提议后停下受影响的部分，等待确认。

## 候选方案

- A：维持现状。内存端口与替身无法放在测试夹具模块，只能分散到各模块的 `src/test`，T04 以后的每个场景测试都要重复实现，违背任务要求与"同一能力只实现一次"。
- B：把内存端口放进 `meridian-engine-agentscope` 的生产代码。测试代码进入生产构件，不可取。
- C：「模块依赖规则」中 `meridian-engine-agentscope-test` 一行改为"common、engine-spi、engine-agentscope、AgentScope（test scope）"，即与 `engine-agentscope` 的依赖范围一致；`MeridianModule.ENGINE_AGENTSCOPE_TEST` 同步放行 `COMMON` 与 `ENGINE_SPI`。

## 决策

采用 C。测试夹具是 `engine-agentscope` 的测试侧，它需要的平台类型就是 `engine-agentscope` 本身使用的那些；放行后依赖方向与原表一致，没有新增的耦合。

## 影响

- 架构设计「模块依赖规则」：`meridian-engine-agentscope-test` 一行改为"common、engine-spi、engine-agentscope、AgentScope（test scope）"；递增版本号。
- `meridian-arch-tests` 的 `MeridianModule`：`ENGINE_AGENTSCOPE_TEST` 的放行列表加上 `COMMON`、`ENGINE_SPI`。
- `meridian-engine-agentscope-test` 的 POM：显式声明对 `meridian-common` 与 `meridian-engine-spi` 的依赖（enforcer 已放行）。
- 确认前，T03 中依赖这些夹具的验收（装配测试、场景测试、P1）不能在全量检查中通过架构测试。
