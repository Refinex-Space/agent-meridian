# meridian-sandbox

会话沙箱的集群侧资源，装在命名空间 `meridian-sandbox`（Pod Security `restricted`）：

- 三个规格的 `SandboxTemplate` 与同名的 `SandboxWarmPool`（`sandbox-small`、`sandbox-medium`、`sandbox-large`，ADR-0036）。
- sandbox-router（服务 `sandbox-router-svc:8080`）：runtime 经它访问沙箱的执行服务。
- 出网代理（Envoy，服务 `meridian-egress-proxy:10000`）：沙箱的 `HTTP_PROXY`、`HTTPS_PROXY` 指向它。代理对每个明文 HTTP 请求与 CONNECT 隧道回调 runtime 的 `POST /internal/v1/egress:authorize`（Lua 过滤器，ADR-0038），只有 200 放行，超时与错误一律拒绝；不终止 TLS。
- 网络策略：沙箱只能访问代理与集群 DNS，入网只允许 router；代理只能访问 DNS、runtime 与公网（排除私网、链路本地、CGNAT、回环），入网只允许沙箱（ADR-0039）。
- runtime 的 Role（`runtimeAccess`）：认领与删除沙箱、读取状态、端口转发。

前提：集群已安装 agent-sandbox v1.0.10（含扩展）与 RuntimeClass `gvisor`，网络插件执行 NetworkPolicy（kind 用 Calico）。

## 出网代理的两种拓扑

授权回调的上游由 `egressProxy.authz.topology` 切换，Envoy 的上游地址与代理的出网策略随之改变：

| 拓扑 | 用于 | 回调地址 | 代理的出网策略放行 |
|---|---|---|---|
| `cluster`（默认） | runtime 部署在集群内（生产） | `authz.cluster.service:authz.cluster.port`（默认 `meridian-runtime.meridian.svc.cluster.local:8080`） | 命名空间 `authz.cluster.namespace` 中带 `authz.cluster.podLabels` 的 Pod |
| `host` | runtime 跑在宿主 JVM（本机 kind 开发） | `authz.host.address:authz.host.port`（`host.docker.internal`，只取 IPv4） | `authz.host.cidr`（Docker Desktop 的宿主地址 `192.168.65.254/32`） |

runtime 在集群内：

```bash
helm upgrade --install meridian-sandbox deploy/helm/meridian-sandbox -n meridian-sandbox \
  --set egressProxy.authz.cluster.service=meridian-runtime.meridian.svc.cluster.local \
  --set egressProxy.authz.cluster.port=8080
```

runtime 在宿主（`values-kind.yaml` 已设置 `topology: host`、端口 18080）：

```bash
scripts/dev/deploy-sandbox.sh meridian
scripts/dev/run-runtime.sh --server.port=18080 \
  --meridian.runtime.sandbox.enabled=true --meridian.runtime.sandbox.kube-context=kind-meridian
```

kind 集群的 Pod 网段不能与 Docker Desktop 的宿主地址重叠，否则 Pod 访问不到 `host.docker.internal`：`scripts/dev/kind-gvisor.sh` 用 `10.244.0.0/16`。

## 回调凭据

代理以 `Authorization: Bearer <凭据>` 调用 runtime 的内部接口，凭据来自 Secret `egressProxy.authz.credentialSecret`（默认 `meridian-egress-proxy-credential`，键 `credential`），以环境变量注入 Envoy。chart 不创建这个 Secret。本机 kind 由 `deploy-sandbox.sh` 用 `deploy/compose/.env`（或 `.env.example`）中的 `MERIDIAN_DEV_SERVICE_CREDENTIAL` 创建，与 `dev` profile 的 runtime 接受的开发期服务凭据相同；T14b 引入服务 JWT 后改为正式的凭据方式。改了 Secret 要重启代理（`kubectl -n meridian-sandbox rollout restart deployment/meridian-egress-proxy`）。

## 其他 values

| 键 | 说明 |
|---|---|
| `egressProxy.upstream.except` | 代理不能访问的网段；白名单里的域名被解析到这些地址时也连不上 |
| `egressProxy.upstream.namespaces` | 代理可以访问的集群内命名空间（本机 kind 放行 P14 的目标服务所在的 `meridian-egress-test`） |
| `egressProxy.limits`、`egressProxy.timeouts` | 连接上限与超时，理由写在 `templates/egress-proxy-config.yaml` 的注释中 |
| `dns.namespace`、`dns.podLabels` | 集群 DNS 的位置，沙箱与代理的出网策略按它放行 53 端口 |

检查：`helm lint deploy/helm/meridian-sandbox`、`helm lint deploy/helm/meridian-sandbox -f deploy/helm/meridian-sandbox/values-kind.yaml`。渲染后的 Envoy 配置可以用 `envoy --mode validate` 校验。
