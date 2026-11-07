# T12b 沙箱出网控制：Envoy、ext_authz 与 NetworkPolicy

| 项 | 内容 |
|---|---|
| 阶段 | 1 M0 内核与运行时核心 |
| 依赖 | T12a |
| 推荐配置 | Opus 5.5，effort xhigh，新会话 |
| 规模 | 中 |
| 关联验证项 | P14 |

启动方式：在仓库根目录开新会话，输入 `/task T12b`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；AgentScope 只以 2.0.4 为准（读法见 `docs/plan/references.md`）。
- 只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

沙箱是平台最主要的安全边界。T12a 让会话拥有了沙箱，本任务控制沙箱能访问哪里：沙箱的出网只能经过代理，代理对每个请求回调 runtime，按环境白名单放行；NetworkPolicy 阻断绕过代理的直连。

每一条出网控制都必须在本机 kind 集群上实测。

## 必读

1. 架构设计「工作区与沙箱」中的「沙箱出网」，「安全架构」中「智能体安全」，ADR-0017。
2. T11、T12a 的模块 `CLAUDE.md`；`scripts/dev/kind-gvisor.sh` 与 `scripts/dev/kind-verify.sh`（集群用 Calico 执行 NetworkPolicy）。
3. Envoy 的 `ext_authz` HTTP 服务与 CONNECT 代理配置，联网查阅官方文档。

## 范围

### 要做

- Envoy 代理（部署在 `meridian-sandbox`），HTTP 与 CONNECT；`ext_authz` 回调 runtime 的 `/internal/v1/egress:authorize`。
- runtime 的出网授权：源 IP 到会话与环境的解析、白名单判定、缓存与失效。
- NetworkPolicy：沙箱 Pod 只能访问代理与 DNS。
- Helm chart 中的 Envoy 部署与配置。
- M0 验证 P14。

### 不做

- TLS 终止与凭据注入（ADR-0017 暂缓，M2 的 T37 再评估）。

## 交付物

### 1. 代理与授权

- Envoy 作为 HTTP 与 CONNECT 代理，对每个请求调用 runtime 的 `/internal/v1/egress:authorize`，携带源 IP 与目标主机。
- runtime 用 `sessions.sandbox_pod_ip` 找到会话与环境，按环境白名单判定，结果缓存 30 秒；Pod IP 被清空后授权立即失效（缓存同时失效）。按 IP 查找会话的仓储继承 `SystemRepository`，放在 `infra.system`。
- 代理不终止 TLS，不注入凭据（ADR-0017）。
- 沙箱镜像中的 `HTTP_PROXY`、`HTTPS_PROXY` 指向代理。

### 2. 本机拓扑

- runtime 跑在宿主 JVM 时，Envoy 的 `ext_authz` 上游地址为 `host.docker.internal:<runtime 端口>`；NetworkPolicy 放行 Envoy 到该地址。部署在集群内时改为 Service 地址，两种方式由 values 切换。
- P14 的白名单内外域名，用集群内的两个服务 `allowed-echo` 与 `denied-echo` 模拟，不依赖公网。

### 3. 验证

| 验证项 | 做法 |
|---|---|
| P14 | 白名单内的域名可以访问；其他域名被拒；沙箱删除后同 IP 的新 Pod 不继承旧授权；绕过代理的直连被 NetworkPolicy 阻断 |

## 实现要点

- 出网授权的测试在 kind 集群中进行：在沙箱 Pod 里用 `curl` 经代理访问 `allowed-echo` 与 `denied-echo`，以及不经代理直接访问 Pod IP。
- 授权接口对每个沙箱请求都会被调用，响应时间进指标（`meridian_egress_authorize_seconds`），缓存命中率可观测。
- 代理配置中的超时与连接上限写明理由。

## 验收标准

- [T12b-A01] P14 的四条全部满足。验证：kind 中的实测输出；报告 `docs/poc/P14-出网控制.md`。
- [T12b-A02] Pod IP 被清空后，30 秒缓存内的授权也立即失效。验证：集成测试。
- [T12b-A03] Helm chart 中 Envoy 与 NetworkPolicy 可以按 values 在"runtime 在宿主"与"runtime 在集群内"两种拓扑间切换；`helm lint` 通过。验证：命令输出。
- [T12b-A04] `scripts/dev/verify.sh` 通过；`-Pkind` 的测试在本机通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 更新 `meridian-runtime` 的 `CLAUDE.md` 与 Helm chart 的 README（两种拓扑的配置方法）。
- 更新 `docs/poc/README.md` 中 P14 的状态。
