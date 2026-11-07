# ADR-0023 AgentScope 不重试改用 maxRetries(1)

| 项 | 内容 |
|---|---|
| 状态 | 已采纳 |
| 日期 | 2026-11-07 |
| 决策人 | T03 执行会话提议，用户于 2026-11-07 采纳 |
| 影响的架构设计章节 | 「引擎装配」表中的 `maxRetries(0)` 一行（"必须"条目） |

## 背景

架构设计「引擎装配」表规定装配时**必须**调用 `maxRetries(0)`，理由是重试只由 `RoutedModel` 针对供应商错误进行（ADR-0006）。T03 在 AgentScope 2.0.4 源码中核对这一行，结论是这个写法不能用：

1. `HarnessAgent.Builder.maxRetries(int)` 直接转发给 `ReActAgent.Builder.maxRetries(int)`，后者在 `maxRetries <= 0` 时抛 `IllegalArgumentException("maxRetries must be > 0")`（`io/agentscope/core/ReActAgent.java` 第 5196 行）。`ModelConfig` 的构造器做同样的校验。
2. 参数的含义是"含首次在内的总尝试次数"（Javadoc："max attempts including the first try"），默认值 `ModelConfig.DEFAULT_MAX_RETRIES = 3`。所以"不重试"对应的是 1，不是 0。
3. 这个值不会让 `ReActAgent` 自己重试。它在 `buildGenerateOptions()` 中变成 `GenerateOptions.executionConfig.maxAttempts`，随每次 `model.stream(...)` 传给模型；真正的重试发生在供应商模型类内部的 `ModelUtils.applyTimeoutAndRetry`，只在 `maxAttempts > 1` 时启用（`io/agentscope/core/model/ModelUtils.java` 第 100 行）。
4. 不调用 `maxRetries` 时默认是 3：`RoutedModel` 把收到的 `GenerateOptions` 原样交给底层的 OpenAI 兼容、DashScope 等模型类时，这些类会自己重试两次，与 `RoutedModel` 的重试叠加，预算与限流异常也会被重试。

按执行协议，AgentScope 的实际行为与架构设计的描述不符属于变更型，所以提议后停下这一行，等待确认。

## 候选方案

- A：维持现状（`maxRetries(0)`）。装配直接抛异常，不可行。
- B：不调用 `maxRetries`，由 `RoutedModel`（T09a）在调用底层模型前把 `executionConfig.maxAttempts` 改为 1。能达到目的，但装配表上看不出这条约束，任何绕过 `RoutedModel` 直接使用底层模型的路径（例如压缩器配置了单独的模型）都会带着默认的 3 次重试。
- C：装配时调用 `maxRetries(1)`，表示"只尝试一次"。同时要求 `RoutedModel` 调用底层模型时不放大这个值（底层构建器的默认 `executionConfig` 只在请求未带 `executionConfig` 时生效，而 `ReActAgent` 每次都会带上）。

## 决策

采用 C。理由：它准确表达了 ADR-0006 的意图（AgentScope 一侧不重试），从装配结果就能读出（`getDelegate().getModelConfig().maxRetries() == 1`，以及 `ScriptedModel` 记录到的 `GenerateOptions.executionConfig.maxAttempts == 1`），并且对所有经 `ReActAgent` 发出的模型调用都生效。

## 影响

- 架构设计「引擎装配」表：`maxRetries(0)` 一行改为 `maxRetries(1)`，原因列补充"参数为含首次在内的总尝试次数，2.0.4 中 0 非法"；递增架构设计版本号。
- 代码：`AgentAssembler.configure` 增加 `builder.maxRetries(1)`；`AgentAssemblerTest` 增加对应断言。确认前这一行不设置，其余装配不受影响。
- T09a：`RoutedModel` 调用底层模型时保持 `maxAttempts` 为 1，在其单元测试中断言。
