# ADR-0046 Anthropic 与 Gemini 用平台适配器接入

| 项 | 内容 |
|---|---|
| 状态 | 已采纳（用户在 T15a 会话中确认） |
| 日期 | 2026-11-11 |
| 决策人 | T15a 提出，用户确认 |
| 影响的架构设计章节 | 模型调用、技术基线（模型 SDK） |

## 背景

Q3 定了首批供应商为 OpenAI 兼容、Anthropic、Gemini（ADR-0045）。架构设计「模型调用」写的是"用 AgentScope 对应的模型类构造底层客户端，按'供应商账号加模型'缓存"，ADR-0032 进一步约定底层客户端不含凭据、凭据与端点在每次调用的选项中给出、底层客户端每次调用只尝试一次。核对 2.0.4 源码与它锁定的 SDK 版本（`anthropic-java` 2.52.0、`google-genai` 1.45.0）后，`AnthropicChatModel` 与 `GeminiChatModel` 做不到这几条：

1. **凭据只能在构造时给出。** 两个类都在构造函数里用凭据建 SDK 客户端，`GenerateOptions.apiKey` 被忽略。`google-genai` 的 `Client` 在 Gemini API 模式下没有密钥就抛异常，并在每个请求上用客户端的密钥覆盖请求级的 `x-goog-api-key`。
2. **SDK 自带重试，关不掉。** `AnthropicChatModel` 用 `AnthropicOkHttpClient.builder()` 的默认 `maxRetries`（2）；`google-genai` 的 `RetryInterceptor` 默认 5 次尝试。AgentScope 的 builder 都没有暴露这两个参数，叠加平台在首包之前的 2 次重试，一次调用最多 9 到 15 次请求，违反"底层客户端每次调用只尝试一次"。
3. **阻塞流在 `boundedElastic` 上迭代。** 两个类都 `subscribeOn(Schedulers.boundedElastic())`，线程上限 10×CPU，正是 P7 把单节点模型调用并发限在 40 的原因（ADR-0041）。
4. **SDK 异常不带平台能识别的状态码。** `AnthropicServiceException`、`google-genai` 的 `ApiException` 不实现 `ModelHttpException`，`ProviderErrors` 会把 429、5xx 当成不可重试，`Retry-After` 也拿不到。
5. **图片地址。** `GeminiMediaConverter` 对 `http(s)` 地址在 runtime 进程内用 `URL.openStream()` 下载（没有 SSRF 防护），对其他地址读本机文件；`AnthropicMediaConverter` 对本地路径读本机文件（登记事项 D52）。

## 候选方案

1. **直接用 AgentScope 的两个模型类**：底层客户端按"账号、模型、端点、凭据指纹"缓存 5 分钟。改动最小，但接受 SDK 重试叠加、`boundedElastic` 的并发上限、拿不到 `Retry-After`；Gemini 的图片地址仍在 runtime 内下载。
2. **平台薄适配器**：平台写两个 `ChatModelBase` 子类，复用 2.0.4 的 formatter 与响应解析器（消息、工具、选项与流式事件的转换是供应商适配的主体），SDK 客户端由平台按自己的约束构造。
3. **推迟 Anthropic 与 Gemini**：本任务只开放 OpenAI 兼容。

## 决策

采用方案 2（用户确认）。

1. **类与复用。** `engine.agentscope.model` 中新增 `AnthropicMessagesModel` 与 `GeminiContentModel`，由 `AnthropicClientFactory`、`GeminiClientFactory` 经 `ProviderClientFactory` 加入 `ProviderClients`。消息、系统提示词、工具、选项的转换与流式事件的解析分别用 2.0.4 的 `AnthropicChatFormatter`、`AnthropicResponseParser`、`GeminiChatFormatter`，不复制它们的代码。构件 `agentscope-extensions-model-anthropic`、`agentscope-extensions-model-gemini`（2.0.4）只被 `meridian-engine-agentscope` 依赖，SDK 版本取它们锁定的 `anthropic-java` 2.52.0、`google-genai` 1.45.0。
2. **凭据。** Anthropic：SDK 客户端按"账号、模型、端点"构造一次，不带凭据，每次调用用 `withOptions` 派生一个带 `x-api-key` 请求头的视图（共用连接池），用完即弃。Gemini：SDK 要求构造时给密钥，适配器内部按凭据的 SHA-256 指纹缓存 SDK 客户端，写入 5 分钟后过期并关闭（与 runtime 凭据明文的缓存上限一致）；`ProviderClients` 缓存的适配器实例本身不含凭据。
3. **只尝试一次。** Anthropic 的 `maxRetries(0)`，Gemini 的 `HttpRetryOptions.attempts(1)`；超时由平台设置（与 OpenAI 兼容的传输相同的连接与读取上限），重试只在 `RoutedModel`。
4. **调度。** SDK 的阻塞流在 `Blocking.scheduler()`（虚拟线程）上迭代与关闭，不用 `boundedElastic`。
5. **错误分类。** 适配器把 SDK 的状态码异常转成 `HttpTransportException(状态码)`（不带响应体，消息中不含凭据）；Anthropic 的 `retry-after` 头记到本次尝试的 `ProviderResponseObserver`，`google-genai` 的 `ApiException` 不带响应头，Gemini 只按指数退避；SDK 的 I/O 异常转成可重试的传输异常。`ProviderErrors` 的分类与 OpenAI 兼容类型一致。
6. **图片地址。** 入口只接受 `http(s)` 的 `image.url`（ADR-0045）：Anthropic 把它作为 URL 来源交给供应商下载；Gemini 适配器遇到 URL 来源的图片或数据块以 `ProviderRejectedException`（终止）结束本次调用，不在 runtime 内下载。平台文件中的图片随 T17b 以内联数据交给模型。
7. **范围。** `ModelGateway.Provider` 中的 `DASHSCOPE`、`OLLAMA` 仍没有实现，control-plane 只允许创建 `openai_compatible`、`anthropic`、`gemini` 三种供应商账号；Embedding 与 rerank 只在 OpenAI 兼容账号下（ADR-0045）。

## 影响

- 架构设计「模型调用」第 1 步与错误处理：Anthropic、Gemini 由平台适配器复用 2.0.4 的 formatter 与解析器；凭据、重试、调度、错误分类的约定同上。技术基线的模型一行补两个扩展构件。版本号递增。
- 代码：`meridian-bom`（两个构件）、`meridian-engine-agentscope`（POM 与 enforcer 放行、两个工厂与适配器、`ProviderClientFactory` 的说明）、runtime 的 `ModelFactoryAutoConfiguration` 登记两个工厂。
- 验证：`meridian-engine-agentscope-test` 的 `model.ProviderAdaptersIT` 用 MockWebServer 模拟两家的流式接口，经真实的 `RoutedModel`：文本与工具调用的流、用量、每次调用的凭据头（同一个底层客户端换凭据）、5xx 时请求数等于平台的 1 加 2 次重试（SDK 不重试）、Anthropic 429 的 `retry-after`、4xx 为拒绝、Gemini 拒绝 URL 图片且不发请求。本机没有两家的真实密钥，没有 `-Plive` 验证，接入真实账号后补跑（T23 的连通性测试）。
- 风险：2.1 升级时两个 formatter 与解析器的签名可能变化（T30 复查）；Gemini 每 5 分钟、每个凭据新建一次 SDK 客户端，多一次 TLS 握手。
