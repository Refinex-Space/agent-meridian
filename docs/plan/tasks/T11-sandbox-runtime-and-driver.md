# T11 沙箱运行时镜像、集群与驱动

| 项 | 内容 |
|---|---|
| 阶段 | 1 M0 内核与运行时核心 |
| 依赖 | T10b |
| 推荐配置 | Opus 5.5，effort high，新会话 |
| 规模 | 大 |
| 关联验证项 | P5 |

启动方式：在仓库根目录开新会话，输入 `/task T11`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；AgentScope 只以 2.0.4 为准（读法见 `docs/plan/references.md`）。
- 只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

需要执行代码的会话运行在沙箱里：每个会话一个 Kubernetes Pod，由 gVisor 隔离，从预热池认领。本任务做沙箱的底层：沙箱镜像、集群侧的模板与预热池、平台的 `SandboxDriver`，并在本机 kind 集群上测出认领与快照的真实耗时。

本机已经验证可行：`scripts/dev/kind-gvisor.sh` 创建带 Calico 与 gVisor 的 kind 集群，`scripts/dev/kind-verify.sh` 验证 gVisor 生效、NetworkPolicy 被执行。不需要额外的 Linux 环境。

## 必读

1. 架构设计「工作区与沙箱」全节，「技术基线」中的沙箱行。
2. `docs/engineering/testing.md`「本机环境」与「代理的两个坑」。
3. `scripts/dev/kind-gvisor.sh`、`scripts/dev/kind-verify.sh`。
4. 实现时查阅（AgentScope 2.0.4 源码，按需定位，不通读）：`agentscope-extensions-sandbox-kubernetes`（`KubernetesFilesystemSpec`、`KubernetesSandboxClientOptions`、`KubernetesSandboxClient`、它对沙箱镜像的接口约定 `/execute`、`/upload`、`/download` 与端口、三种连接方式）、harness 的 `Sandbox`、`SandboxClient`、`SandboxContext`、`SandboxSnapshotSpec`、`RemoteSnapshotSpec`、`RemoteSnapshotClient`。
5. kubernetes-sigs/agent-sandbox v1.0.10 的发布说明与 CRD（`SandboxTemplate`、`SandboxWarmPool`、`SandboxClaim`），联网查阅官方仓库。

## 范围

### 要做

- `sandbox-runtime/`：沙箱镜像与其中的执行服务。
- `deploy/helm/meridian-sandbox/`：agent-sandbox 控制器的安装方式、三个规格的 `SandboxTemplate` 与 `SandboxWarmPool`、默认拒绝出网的 NetworkPolicy。
- `engine.agentscope.sandbox`：`SandboxDriver` 实现、`SingleStartSandbox`、快照存储客户端（对象存储）。
- M0 验证 P5。

### 不做

- 会话级的生命周期管理、外部 `SandboxContext` 的注入、`bash` 工具（T12a）、出网代理（T12b）。

## 交付物

### 1. 沙箱镜像 `sandbox-runtime/`

- 基于 Debian slim，非 root 用户运行，工作目录 `/workspace`。
- 预装 Python 3（uv 管理）、Node.js，以及文档处理所需的库（python-docx、openpyxl、python-pptx、pypdf、pandas、matplotlib），清单写在 `sandbox-runtime/README.md`，与待决问题 Q4 关联。
- 执行服务（Python FastAPI）实现 AgentScope Kubernetes 扩展约定的接口，监听 8888 端口。接口的请求与响应格式以 2.0.4 扩展源码为准，逐字段对齐。命令执行有超时、输出上限，进程组整体终止。
- pytest 测试覆盖执行服务的每个接口；Dockerfile 可在 arm64 与 amd64 上构建。

### 2. 集群侧

- 本机集群：`scripts/dev/kind-gvisor.sh meridian`，然后 `scripts/dev/kind-verify.sh meridian` 必须 PASS。
- agent-sandbox 控制器按官方 v1.0.10 的发布清单安装（写成 `scripts/dev/install-agent-sandbox.sh`，下载走 `MERIDIAN_DOWNLOAD_PROXY`，校验版本）。
- Helm chart：三个规格（`small`、`medium`、`large`，资源上限按架构设计）各一套 `SandboxTemplate` 与 `SandboxWarmPool`；模板要求 `runtimeClassName: gvisor`、非 root、根文件系统只读、`/workspace` 与 `/tmp` 为 emptyDir、去掉全部 capabilities、`seccompProfile: RuntimeDefault`、`HTTP_PROXY`/`HTTPS_PROXY` 指向出网代理的服务地址（代理本身在 T12b）；命名空间 `meridian-sandbox`；NetworkPolicy 默认拒绝全部出网。
- 镜像用 `kind load docker-image` 加载进集群。
- 本机开发用的 values（`values-kind.yaml`）：资源 requests 取 limits 的 1/8；`small` 预热池 2 个，`medium` 与 `large` 预热池 0 个。8 GB 的 Docker 装不下 `large` 规格的预热池，P5 只测 `small`，其余规格在报告中注明"按需扩容验证"。

### 3. `SandboxDriver`

- 实现 `engine-spi` 中的接口：创建（从指定规格的预热池认领）、恢复（从快照）、快照、删除。参数与返回值是平台类型 `SandboxHandle` 与序列化后的状态，内部使用 AgentScope 的 `KubernetesSandboxClient`。
- `SingleStartSandbox`：包装 AgentScope 的 `Sandbox`，保证 `start()` 只真正执行一次且线程安全。
- 快照：工作区打 tar 存入对象存储（实现 `RemoteSnapshotClient`，路径 `rt/snapshots/{tenant}/{project}/{session}/{snapshot_id}.tar`），恢复时下载并展开。
- runtime 连接沙箱的方式：本机开发用扩展支持的端口转发方式；记录生产环境推荐的方式（网关或直连）。

### 4. P5 测量

- 认领耗时：预热池命中时连续认领 50 次，记录 p50、p95、p99。
- 快照与恢复耗时：工作区 10 MB 与 100 MB 两档。
- 预热池补充：认领后池子补充到目标水位的时间。
- 报告 `docs/poc/P05-沙箱与预热池.md`：环境（kind、gVisor 版本、Docker 内存）、方法、数据、与架构设计目标（认领 p95 小于 3 秒）的对比、结论。

## 实现要点

- 需要启动 kind 集群前检查 `docker info` 的内存；不足 8 GB 可用时，按执行协议在报告开头提醒用户，并先做不依赖集群的部分（镜像、执行服务测试、驱动单测）。
- kind 节点的代理只用 `kind-gvisor.sh` 中的设置，不要把宿主 shell 的代理变量传进集群。
- 集成测试默认不依赖 kind（在 CI 中不可用）：把需要 kind 的测试标为单独的 JUnit 标签 `kind`，由 `-Pkind` 运行，并在模块 `CLAUDE.md` 中写清。

## 验收标准

- [T11-A01] 沙箱镜像构建成功，以非 root 运行；执行服务的 pytest 全部通过。验证：构建与测试输出。
- [T11-A02] 执行服务接口与 AgentScope 2.0.4 扩展的约定逐字段一致。验证：对照表（扩展源码位置与服务实现位置）加接口测试。
- [T11-A03] `kind-verify.sh meridian` 输出 PASS；沙箱 Pod 内 `uname -r` 含 `gvisor`。验证：命令输出。
- [T11-A04] 三个规格的模板与预热池部署成功，Pod 的安全上下文符合要求。验证：`kubectl get` 与 Pod spec 的关键字段。
- [T11-A05] `SandboxDriver` 能完成认领、执行命令、上传下载文件、快照、删除、从快照恢复，恢复后文件一致。验证：`kind` 标签的集成测试。
- [T11-A06] `SingleStartSandbox` 在并发调用 `start()` 时只执行一次。验证：并发测试。
- [T11-A07] P5 数据完整，报告已写。验证：报告路径与关键数值。
- [T11-A08] `scripts/dev/verify.sh` 通过（不含 `kind` 标签）；`-Pkind` 的测试在本机通过。验证：两条命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 创建 `sandbox-runtime/` 的 `CLAUDE.md`；更新 `meridian-engine-agentscope` 的 `CLAUDE.md`。
- 在根 `CLAUDE.md` 的命令一节加入 kind 集群的创建、验证、删除命令。
- 测试结束后保留 `meridian` 集群供 T12a、T12b 使用，并在报告中说明；如果用户需要释放内存，删除命令为 `kind delete cluster --name meridian`。
