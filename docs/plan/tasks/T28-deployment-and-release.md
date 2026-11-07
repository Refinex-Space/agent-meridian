# T28 部署与发布

| 项 | 内容 |
|---|---|
| 阶段 | 5 M1 交付 |
| 依赖 | T27、R3 |
| 推荐配置 | Opus 5.5，effort high，新会话 |
| 规模 | 大 |
| 关联验证项 | 无 |

启动方式：在仓库根目录开新会话，输入 `/task T28`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

本任务让平台可以部署：每个服务的镜像、完整的 Helm chart、数据库迁移作业、扩缩容与中断预算、凭据同步、沙箱节点池，以及一套私有化部署手册。验证在本机 kind 集群上完成，达到"一条命令部署整个平台"。

## 必读

1. 架构设计「部署架构」全节、「部署单元」、「非功能需求」、「工作区与沙箱」中的集群侧要求。
2. 已有的 `deploy/helm/meridian-sandbox/`、`deploy/compose/`。
3. 调用 `doc-craft` 技能撰写部署手册。

## 范围

### 要做

- 镜像：三个 Java 服务（分层构建，非 root，只读根文件系统可运行）、doc-parser、console（静态资源加 Nginx 或同等）、sandbox-runtime；多架构（arm64、amd64）；镜像标签规则（版本加 git 提交号）。
- Helm：`deploy/helm/agent-meridian/` 伞形 chart，包含三个服务、doc-parser、console、outbox Relay、Flyway 迁移 Job（服务启动前执行）、Ingress 路由（按架构设计的路径路由）、HPA（runtime 按调度队列深度与 CPU）、PodDisruptionBudget、`terminationGracePeriodSeconds: 90`、External Secrets 的示例配置；子 chart 引用 `meridian-sandbox`。
- 命名空间与网络：`meridian-system`、`meridian-sandbox`、`meridian-observability`；沙箱节点池的污点与容忍；平台服务与沙箱之间的 NetworkPolicy。
- 数据组件：默认对接外部托管服务；提供 values 示例。
- 发布流程：GitHub Actions 的发布工作流（构建、测试、推送镜像到可配置的镜像仓库、打包 chart）；**本任务不实际推送镜像与发布**，只在本地构建验证。
- 私有化部署手册 `docs/deploy/`：前置条件、安装、配置项说明、升级（滚动发布与迁移）、备份与恢复（RPO 与 RTO 的实现方式）、离线安装（镜像打包与导入）、常见问题。

### 不做

- 真实的云环境部署；推送镜像；发布版本（需要用户授权）。

## 实现要点

- 在 kind 中完整部署时内存占用较大：先检查 Docker 可用内存，不足时提醒用户并只部署核心服务。
- 集群拓扑：给 `scripts/dev/kind-gvisor.sh` 增加 worker 节点数参数，用一个控制面节点加两个 worker 的集群验证；给其中一个 worker 打沙箱污点，验证沙箱 Pod 只调度到它、平台 Pod 不调度到它。
- HPA 的队列深度指标经 KEDA 提供（读取调度队列深度的 Prometheus 指标），KEDA 作为 chart 的可选依赖；未安装 KEDA 时退化为只按 CPU 扩缩。
- 滚动发布验证：部署两个 runtime 副本，在会话执行中滚动更新 runtime，业务方只看到一次 `session.status_rescheduled`，会话继续完成。

## 验收标准

- [T28-A01] 全部镜像在本机构建成功，以非 root 运行；Java 服务在只读根文件系统下正常启动。验证：构建输出与运行检查。
- [T28-A02] `helm install` 在 kind 集群中一次部署整个平台，迁移 Job 先于服务执行，全部 Pod 就绪。验证：命令输出与 `kubectl get pods`。
- [T28-A03] 部署后通过 Ingress 完成一次端到端对话（API Key），控制台可登录。验证：命令与截图。
- [T28-A04] 滚动更新 runtime 时，进行中的会话只出现一次 `session.status_rescheduled` 并最终完成。验证：测试记录。
- [T28-A05] HPA（KEDA 队列深度与 CPU）、PDB、NetworkPolicy、沙箱节点污点生效（沙箱 Pod 只调度到沙箱节点、只能访问出网代理；平台服务之间按需互通）。验证：三节点 kind 集群中的 `kubectl` 输出与连通性测试。
- [T28-A06] `helm lint` 与 `helm template` 通过；发布工作流语法有效。验证：命令输出。
- [T28-A07] 部署手册经一个只读手册的子代理按步骤在新建的 kind 集群上完成安装。验证：子代理的执行记录。
- [T28-A08] `scripts/dev/verify.sh` 通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 在 `docs/README.md` 的索引中加入 `deploy/`；在根 `CLAUDE.md` 中加入部署相关命令。
