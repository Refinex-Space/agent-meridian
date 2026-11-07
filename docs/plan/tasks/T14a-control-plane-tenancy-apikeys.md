# T14a control-plane 基础：租户、成员、API Key 与限流

| 项 | 内容 |
|---|---|
| 阶段 | 2 M1 控制面与治理 |
| 依赖 | R1（结论为进入 M1） |
| 推荐配置 | Opus 5.5，effort high，新会话 |
| 规模 | 大 |
| 关联验证项 | 无 |

启动方式：在仓库根目录开新会话，输入 `/task T14a`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；AgentScope 只以 2.0.4 为准（读法见 `docs/plan/references.md`）。
- 只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

M0 中 runtime 用开发期的 API Key 与文件配置运行。本任务建立 control-plane 服务和租户体系：租户、项目、成员、角色、API Key、按 Key 限流、租户并发配额，并让 runtime 切换到真实的 API Key 认证。控制台登录（OIDC）与服务间 JWT 在 T14b。

这是所有治理能力的地基：之后的模型目录、凭据、预算、审计都挂在租户与项目上。

## 必读

1. 架构设计：「部署单元」（control-plane 行）、「领域模型」、「数据架构」（control-plane 部分与「租户隔离」）、「认领」中的租户并发计数行、「安全架构」中的认证与授权、「API 设计」中的「约定」、「本地开发」。
2. `docs/engineering/coding-standards.md`。
3. 已有模块 `CLAUDE.md`：`meridian-security`、`meridian-persistence`、`meridian-runtime`、`meridian-api`。
4. `docs/poc/M0-总结.md` 中的前提条件。

## 范围

### 要做

- `meridian-control-plane` 应用骨架：Spring Boot MVC 加虚拟线程、`cp` schema 迁移、健康检查、profile。
- 租户（含 `max_concurrent_turns`、`event_retention_days`）、`tenant_settings`、项目、成员、角色绑定、API Key 的数据模型与管理接口。
- 管理 API 规范 `meridian-admin-v1.yaml`（控制台使用），写 ADR 记录"公开 API 与管理 API 分为两份规范"（补充型）。
- 授权：角色矩阵，声明式实现。
- API Key：生成（`mk_live_` 前缀，明文只返回一次）、SHA-256 哈希存储、作用域、吊销（经 Pub/Sub `key.revoked` 立即失效）、内部校验接口。
- `meridian-security` 的 API Key 认证（经内部接口校验，结果缓存 60 秒）；runtime 切换到真实认证，`DevApiKeyResolver` 只留在测试中。
- 按 API Key 的限流（Redis 令牌桶），响应带 `RateLimit-*` 头，超限 429 加 `Retry-After`。
- 租户并发配额下发到 runtime。
- Docker Compose 的 `app` profile。
- 管理操作的审计事件（outbox `audit` 主题；消费者在 T16）。

### 不做

- OIDC、Keycloak、服务 JWT（T14b）。本任务中 control-plane 与 runtime 之间的内部调用沿用开发期的静态服务凭据。
- 模型、凭据、智能体等目录资源（T15a、T15b）、计费与审计查询（T16）、控制台页面（T21 起）。

## 交付物

### 1. 数据模型

`cp` 中的 `tenants`、`tenant_settings`、`projects`、`members`、`role_bindings`、`api_keys`，以及 `outbox`。角色按架构设计「授权」表：租户所有者、租户管理员、项目开发者、项目观察者、审批人。`tenants`、`members`、`role_bindings` 是租户级仓储；按哈希查找 API Key 的仓储继承 `SystemRepository`，放在 `infra.system`。

### 2. 管理 API（`meridian-admin-v1.yaml`）

租户与项目、成员与角色、API Key 的增删改查。所有写操作支持 `Idempotency-Key`（复用 `persistence.idempotency`），错误为 problem+json，列表为游标分页。

### 3. 授权

- 授权检查集中在 `security` 中声明式实现（注解或路由配置），业务代码不散落角色判断。
- 角色矩阵用表驱动测试覆盖：每个角色对每类操作的允许与拒绝。本任务用测试注入的身份驱动，T14b 接上 OIDC 后复用同一张表。

### 4. 租户并发配额

- `cp.tenants.max_concurrent_turns`，默认值放配置；runtime 经内部接口读取。
- runtime 为某租户创建第一条轮次时插入计数行（T05 已实现插入），`max_running` 改为取该租户的配额。
- control-plane 修改配额时发布 `config.invalidate`，runtime 收到后更新 `rt.tenant_concurrency.max_running`。

### 5. Compose `app` profile

`deploy/compose` 增加 `app` profile，包含 control-plane 与 runtime，用 Jib 构建开发镜像；`ScriptedOpenAiServer` 作为一个容器加入同一 profile，供端到端测试使用。T18 再加入 knowledge 与 doc-parser。

## 实现要点

- API Key 明文只在创建响应中出现一次；日志、事件、审计中只出现前 8 位。
- 吊销的传播：control-plane 发布 `key.revoked`，各服务清除缓存；测试证明吊销后 1 秒内请求被拒。

## 验收标准

- [T14a-A01] control-plane 启动，`cp` 迁移成功，健康检查通过。验证：集成测试。
- [T14a-A02] 角色矩阵表驱动测试全部通过。验证：测试报告。
- [T14a-A03] API Key：创建时返回一次明文；以哈希存储；作用域限制生效；吊销后 1 秒内 control-plane 与 runtime 都拒绝该 Key。验证：集成测试。
- [T14a-A04] 限流：超过速率时返回 429 与 `Retry-After`，正常请求带 `RateLimit-*` 头。验证：集成测试。
- [T14a-A05] runtime 在非测试 profile 下不再使用 `DevApiKeyResolver`。验证：测试。
- [T14a-A06] 管理操作产生审计 outbox 记录，不含 Key 明文。验证：集成测试。
- [T14a-A07] 新建租户后，不手工插入任何数据就能完成一轮会话；修改该租户的并发配额后，runtime 中的 `max_running` 随之更新。验证：集成测试。
- [T14a-A08] `docker compose --profile app up` 启动 control-plane、runtime 与脚本化模型服务，经 API Key 跑通一轮会话。验证：命令输出。
- [T14a-A09] `scripts/dev/verify.sh` 通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 创建 `meridian-control-plane` 的 `CLAUDE.md`；更新 `meridian-security`、`meridian-runtime` 的 `CLAUDE.md`。
- 在根 `CLAUDE.md` 中加入启动 control-plane 与 compose `app` profile 的命令。
