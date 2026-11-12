# meridian-model-client

knowledge 调用 runtime 内部模型接口的 HTTP 客户端（架构设计「写入流程」第 4 步）：目前只有 Embedding（`POST /internal/v1/embeddings`），rerank 由 T19 加入。不直接调用供应商，不做限流、预算与计量（都在 runtime）。

## 命令

- 编译与单测：`./mvnw -pl meridian-model-client -am test`（`EmbeddingClientTest` 用 mockwebserver3 模拟 runtime）

## 包地图

- `EmbeddingClient`：一次至多 `MAX_BATCH`（64）条，调用方分批；结果按 `index` 排回输入顺序；传入的 `RestClient` 由 `ServiceRestClients.create(runtimeUrl, ServiceJwts.RUNTIME)` 构造（带服务 JWT）。
- `EmbeddingRequest`、`EmbeddingResult`、`ModelClientException`（`retryable()`：429、5xx、连接失败与不完整的响应为真；其余 4xx 为假，`code()` 沿用 runtime 的错误码，例如 `model.not_found`）。

## 不变量

- 只依赖 common 与 security（架构设计「模块依赖规则」）：不引用 `meridian-api` 的 DTO，请求与响应是本模块的 record（字段与 `meridian-internal-v1.yaml` 一致，改规范时同步）。

## 对外接口

knowledge 的 `ingestion.infra.RuntimeEmbeddings` 把 `ModelClientException` 转成写入作业的可重试与不可重试失败。
