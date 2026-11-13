# meridian-test-support

集成测试夹具：单例 Testcontainers 容器（PostgreSQL + pgvector + zhparser、Redis、MinIO、Keycloak）、Spring 属性注册、纯 JDBC 的迁移与清理、令牌签发方，以及脚本化模型服务 `ScriptedOpenAiServer`（OpenAI 兼容的本地假供应商）。只作为其他模块的 test 作用域依赖；脚本化模型服务另可独立启动。

## 命令

- 夹具自测：`./mvnw -pl meridian-test-support -am verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false`
- 脚本化模型服务自测（不需要 Docker）：`./mvnw -pl meridian-test-support -am test -Dtest=ScriptedOpenAiServerTest -Dsurefire.failIfNoSpecifiedTests=false`
- 独立启动脚本化模型服务：`scripts/dev/scripted-model.sh [--port=18090] [--host=127.0.0.1] [--scripts=<目录>]`，默认端口 **18090**（与 `dev-config/providers/scripted.yaml` 一致），前台运行，Ctrl-C 停止。`dev` profile 的 runtime 用模型 `mdl_dev_scripted`、`mdl_dev_scripted_embedding`、`mdl_dev_scripted_rerank` 调用它。T23 起它还回应 `GET /v1/models`（`scripted`、`scripted-embedding`），供应商账号的连通性测试可以连它。

## 包地图

- `MeridianContainers`：`postgres()`、`redis()`、`minio()`，首次访问时启动，同一 JVM 共用。
- `PostgresImage`：从 `deploy/docker/postgres/` 构建 `agent-meridian/postgres:test-<内容摘要>`，本机已有同摘要镜像时跳过构建。
- `TestDatabase`：`appDataSource()`（`meridian_app`，只有 DML）、`migratorDataSource()`、`migrate(schema)`、`runScript(schema, 资源)`、`truncate(schema)`。
- `SpringContainerProperties`：`@DynamicPropertySource` 中调用 `postgres`、`redis`、`minio`。
- `auth.KeycloakRealm`（ADR-0044）：Testcontainers 的 Keycloak 模块，导入 `deploy/keycloak/meridian-dev-realm.json`（从工作目录向上查找）；`issuer()`、`user(名字, 组…)`（经管理 REST API 创建，返回 `sub`，同名只建一次）、`token(名字)`（客户端 `meridian-dev-cli` 的口令换取）。启动后在容器内用 `kcadm.sh` 关掉 master realm 的 HTTPS 要求，否则宿主经端口映射调用管理 API 得到 `HTTPS required`。
- `auth.TestTokenIssuer`（ADR-0044）：JDK HTTP 服务上的 `/oauth2/jwks`、`/oauth2/token`（`client(服务, 密钥)` 登记）、OIDC 发现文档；`serviceToken`、`sign`（任意声明）、`signWithForeignKey`、`rotate()`、`route(路径, 处理器)` 挂替身接口；执行器是虚拟线程（替身接口会回头读本服务的 JWKS）。
- `mcp.TestMcpServer`（ADR-0047）：嵌入式 Tomcat 在回环地址的随机端口上注册 MCP SDK 0.17.2 的 Streamable HTTP（`/mcp`）与 SSE（`/sse`）传输，校验 `Authorization: Bearer {令牌}`，工具 `lookup_order`、`create_ticket`；`requests()` 记下每个请求的 `Authorization`。control-plane 的登记探测与 runtime 的会话测试共用。
- `outbox.OutboxSamples`（T16，ADR-0048）：runtime 与 knowledge（`knowledge_storage_gb_days`、T19 的 `knowledge_search_calls`）写出的用量（`usage/<kind>.json`）与审计（`audit/<action 的点换成下划线>.json`）消息的契约样本，在 `src/main/resources/cn/refinex/meridian/testsupport/outbox/`；runtime 的契约测试断言消息记录与样本一致，control-plane 的消费者测试替换租户等取值后把样本发布到 Stream。
- `contract.ContractCoverage`（R2）：按规范的路径模板记录被校验过的交互（真实路径，不由调用方自报操作名），三个服务的 `ContractCoverageIT` 用它核对覆盖。
- `model.ScriptedOpenAiServer`：`builder().script(yaml).scriptsDirectory(dir).start()`（默认回环地址的随机端口）；`baseUrl()` 作供应商账号的 `base_url`，`rerankBaseUrl()` 作 rerank 模型的 `base_url`；`calls()` 记录每次请求（路径、脚本、步骤、是否流式；对话补全另有请求体原文 `body`，T10b 比较请求前缀用），断言重试次数用。`ScriptLoader` 解析 YAML，`ScriptedVectors` 是 Embedding 与 rerank 的确定算法。

## 脚本化模型服务

- 端点：`POST /v1/chat/completions`（流式 SSE 以 `[DONE]` 结束，`stream_options.include_usage` 时多一个用量块；非流式）、`POST /v1/embeddings`（文本 SHA-256 作种子的单位向量，默认 1024 维，支持 `dimensions`、`encoding_format=base64`）、`POST /compatible-api/v1/reranks`（DashScope 兼容格式，关键词重叠率打分）。不读密钥，不访问外网。
- 选脚本：请求中最近一条以 `#script:<名称>` 开头的用户消息；没有标记时回显 `echo: <最后一条用户消息>`；未知脚本先重新读 `--scripts` 目录（运行中写进去的脚本也能用，R2），仍没有返回 400。服务无状态：步骤 = 该消息之后的 assistant 消息数，超出步数时回答"脚本 x 已执行完毕。"。工具调用 ID 由脚本名、标记消息的位置与步骤确定地生成：同一请求同一 ID，同一会话的不同轮次不重复。
- 脚本格式（`src/main/resources/cn/refinex/meridian/testsupport/model/scripts/<名称>.yaml`，未知的键让加载失败）：

  ```yaml
  name: tool-once
  description: 调用一次工具后回答
  steps:
    - text: 我先查一下订单。          # 文本；reasoning: 思考内容
      tool_calls:                    # id 可省略；arguments 是映射或 JSON 文本
        - name: lookup_order
          arguments: {order_id: o-1001}
      chunk_size: 8                  # 流式每块字符数，默认 8；delay_ms：块间延迟
      usage: {prompt_tokens: 10, completion_tokens: 5}   # 省略时按字符数估算
    - error: {status: 503, message: 不可用, retry_after: 1}   # 首包之前的 HTTP 错误
    - text: 会断开的回答
      fail_after_chunks: 2           # 发出 2 块后断开连接（首包之后断流）
  ```

- 内置脚本（`ScriptLoader.BUILTIN`，新增时同时加文件与清单）：`chat`（普通对话）、`tool-once`（调用 `lookup_order` 后回答）、`approval`（调用需确认的 `refund_order`）、`custom-tool`（调用会话声明的自定义工具 `get_weather`）、`long-stream`（约 11 秒逐块输出，测 steer 与中断）、`subagent`（调用 `spawn_subagent`，参数 `subagent`、`task`）、`error-before-first-chunk`（首包前 503，每次都失败）、`error-after-first-chunk`（两块后断流）。脚本里的工具名要与被测智能体的工具一致。

## 不变量

- Keycloak 镜像 `quay.io/keycloak/keycloak:26.4.7` 与 Compose 的 `auth` profile 一致，修改时两处一起改。
- 不依赖任何平台模块：`meridian-persistence` 的集成测试依赖本模块，反向依赖会形成 Maven 循环（ADR-0020）。
- 镜像版本与 `deploy/compose/compose.yaml` 一致，修改时两处一起改；MinIO 用按摘要固定的 Chainguard 镜像。
- 迁移约定与 `persistence.flyway.MigrationConventions` 一致：`classpath:db/migration/<schema>`，历史表在该 schema 内。
- 脚本化模型服务的响应格式以 OpenAI 官方格式为准，runtime 的契约测试 `ScriptedProviderContractIT` 用真实的 OpenAI 兼容客户端核对；改格式先跑它（ADR-0033）。

## 易错点

- 容器跨测试类共用，数据会保留：用随机 ID 隔离，或开始前 `truncate` 自己的 schema。
- 首次构建 PostgreSQL 镜像约 2 分钟；需要代理时设置 `MERIDIAN_DOCKER_BUILD_PROXY`（例如 `http://http.docker.internal:3128`）。
- 改了 `deploy/docker/postgres/` 下任何文件都会触发重建，旧的 `test-*` 镜像不会自动删除。
- `error-before-first-chunk` 每次都失败（服务无状态）：runtime 重试 2 次后本轮以可重试错误结束并重新排队，用完要删除会话，否则 Worker 会继续重试。
- 首包后断流靠"声明固定长度、提前关闭连接"实现，客户端读到 `IOException`；分块响应在关闭时总会写结束块，做不到断流。
- 独立启动时类路径上没有 SLF4J 实现，日志不输出；`scripted-model.sh` 自己探测端口并打印启动信息。
