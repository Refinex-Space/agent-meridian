# ADR-0038 出网授权回调改用 Envoy Lua 过滤器

| 项 | 内容 |
|---|---|
| 状态 | 已采纳 |
| 日期 | 2026-11-08 |
| 决策人 | T12b 执行会话提议，用户于 2026-11-08 采纳 |
| 影响的架构设计章节 | 「技术基线」中"沙箱出网代理"一行（立项时锁定）；「沙箱出网」第 3 条的实现方式 |

## 背景

架构设计「技术基线」锁定"沙箱出网代理：Envoy（`ext_authz` 回调 runtime 做域名授权）"，「沙箱出网」第 3 条要求 Envoy 对每个 CONNECT 或 HTTP 请求，带上源 IP 调用 runtime 的 `/internal/v1/egress:authorize`。HTTPS 经 CONNECT 隧道，所以 CONNECT 必须能被授权。

T12b 在本机 kind 集群用 Envoy v1.40.2（`envoyproxy/envoy:distroless-v1.40.2`）实测 `ext_authz` 的 HTTP 服务模式（`http_service`，`path_override: /internal/v1/egress:authorize`，`headers_to_add` 带 `%DOWNSTREAM_REMOTE_ADDRESS_WITHOUT_PORT%` 与 `%REQ(:authority)%`），授权服务记录收到的每个请求：

| 沙箱发出的请求 | 授权服务收到的检查请求 | 结果 |
|---|---|---|
| `POST http://allowed-echo…/foo`，带 `Authorization: Bearer <沙箱自己的令牌>` | `POST /internal/v1/egress:authorize`，**带着沙箱的 `Authorization` 头** | 放行正确 |
| `GET http://denied-echo…/` | `GET /internal/v1/egress:authorize` | 拒绝正确 |
| `CONNECT allowed-echo…:80`（`curl -p`） | **`CONNECT allowed-echo…:80 HTTP/1.1`**，`path_override` 没有生效 | 授权服务返回 200，沙箱仍收到 403 |
| `CONNECT denied-echo…:80` | 同上，`CONNECT` | 403 |

原因在 Envoy 源码 `source/extensions/filters/common/ext_authz/ext_authz_http_impl.cc`：检查请求的请求头逐个复制自客户端请求，`:method` 属于总是包含的头（`Host`、`Method`、`Path`、`Content-Length`、`Authorization`），`path_prefix`/`path_override` 只在遇到 `:path` 时改写，而 CONNECT 没有 `:path`。于是检查请求本身成了一个 CONNECT：上游把 2xx 响应当作隧道建立，检查调用等不到结束，按失败处理为 403。另外两条路都走不通（同一集群实测）：

- `headers_to_add` 不能改写伪头：Envoy 启动校验报 `:-prefixed or host headers may not be modified`。
- `disallowed_headers` 去掉 `:method` 后，检查请求根本发不出去，所有请求都被拒绝。

所以 `ext_authz` 的 HTTP 服务模式只能授权明文 HTTP，授权不了 HTTPS，满足不了「沙箱出网」与 P14。默认还会把沙箱请求里的 `Authorization` 头转发给 runtime（可以用 `disallowed_headers` 去掉）。按执行协议，第三方组件的实际行为与架构设计的描述不符，属于变更型，提议后停下受影响的部分。

## 候选方案

- **A：Envoy 内置的 Lua 过滤器做回调**（`envoy.filters.http.lua`，官方镜像自带）。在 HTTP 过滤器链中放在动态转发代理之前，`envoy_on_request` 用 `httpCall` 向 runtime 集群发 `POST /internal/v1/egress:authorize`，JSON 请求体带源 IP（`streamInfo():downstreamDirectRemoteAddress()`，即 TCP 对端地址，沙箱无法伪造）、目标（`:authority`）与方法；响应不是 200、超时或出错一律以 403 结束（失败即拒绝）。HTTP 与 CONNECT 走同一段代码。检查请求由脚本从头构造，沙箱的请求头不会到达 runtime；内部接口是普通的 `POST` JSON，可以写进 `meridian-internal-v1.yaml` 并做契约校验。脚本约 25 行，只做"转发并执行结论"，判定逻辑全在 runtime。T12b 已在 kind 中实测四种情况（HTTP 与 CONNECT 各放行、拒绝一次）全部正确，授权服务收到的都是 `POST /internal/v1/egress:authorize`。代价：脚本要自己转义 JSON（目标来自沙箱），Lua 过滤器在官方文档中是稳定扩展，但脚本出错会让所有出网失败（失败即拒绝，不会放开）。
- **B：`ext_authz` 的 gRPC 服务模式。** `CheckRequest` 里有源地址与完整的请求属性，CONNECT 没有问题。runtime 要新增 gRPC 服务端口与依赖（grpc-java、protobuf-java、Envoy 的 `envoy.service.auth.v3` 协议定义，或在构建中引入 protoc 生成），内部接口不再是 `/internal/v1/egress:authorize`，服务凭据要走 gRPC 元数据。改动面大，属于引入新的组件与协议。
- **C：维持 `ext_authz` HTTP 服务模式，只授权明文 HTTP，拒绝全部 CONNECT。** 沙箱无法访问任何 HTTPS 站点（pip、npm、git 都走 HTTPS），白名单形同虚设，P14 不通过。
- **D：在 Envoy 旁边另部署一个授权适配服务**（例如自写的小服务或 OPA），由它把 `ext_authz` 的检查转成对 runtime 的调用。CONNECT 的检查请求问题仍在（Envoy 发给它的依然是 CONNECT），还多一个部署单元。

## 决策

采用 A：组件仍是 Envoy，回调的目标、时机与语义（每个 HTTP 或 CONNECT 请求带源 IP 调 `/internal/v1/egress:authorize`，结果由 runtime 决定）与架构设计一致，只是回调由 Lua 过滤器而不是 `ext_authz` 过滤器发出。它是唯一在实测中让 CONNECT 被正确授权、又不给 runtime 增加协议与依赖的方案。

## 影响

- 架构设计：「技术基线」"沙箱出网代理"一行改为"Envoy（Lua 过滤器回调 runtime 做域名授权）"；「沙箱出网」第 3 条写明回调由 Lua 过滤器发出、HTTP 与 CONNECT 同一路径、失败即拒绝、不转发沙箱的请求头；「架构决策记录」表增加本 ADR；版本号递增。
- 代码与配置：`deploy/helm/meridian-sandbox` 的 Envoy 配置用 Lua 过滤器；`meridian-internal-v1.yaml` 增加 `POST /internal/v1/egress:authorize`（JSON 请求体：`source_ip`、`target`、`method`；响应体给出结论与原因）；runtime 的 `egress` 上下文实现它。没有数据迁移。
- 验证：P14 的四条在 kind 中实测；Lua 脚本的 JSON 转义与失败即拒绝由 kind 用例覆盖（授权服务不可达时请求被拒绝）。
- 风险：Envoy 升级时 Lua API（`httpCall`、`streamInfo`）的兼容性，升级 Envoy 版本时重跑 P14 的 kind 用例。
- 不受本决策影响、T12b 继续完成的部分：runtime 按 Pod IP 解析会话与环境、白名单判定、缓存与失效、指标；NetworkPolicy；Envoy 的部署、监听、CONNECT 与动态转发代理；两种拓扑的 values；`allowed-echo`、`denied-echo`。
