# T14b 身份：OIDC、Keycloak 与服务 JWT

| 项 | 内容 |
|---|---|
| 阶段 | 2 M1 控制面与治理 |
| 依赖 | T14a |
| 推荐配置 | Opus 5.5，effort high，新会话 |
| 规模 | 中 |
| 关联验证项 | 无 |

启动方式：在仓库根目录开新会话，输入 `/task T14b`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；AgentScope 只以 2.0.4 为准（读法见 `docs/plan/references.md`）。
- 只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

T14a 建好了租户、角色与 API Key。本任务补上另外两种身份：控制台用户经 OIDC 登录，服务之间用短期 JWT 互相调用。完成后，开发期的静态服务凭据从非测试代码中消失。

## 必读

1. 架构设计「安全架构」全节（认证表中控制台用户调用公开 API 的方式）、「API 设计」中的「约定」。
2. 已有模块 `CLAUDE.md`：`meridian-security`、`meridian-control-plane`、`meridian-runtime`。

## 范围

### 要做

- OIDC：本地用 Keycloak（Docker Compose 的 `auth` profile，导入 realm 配置），控制台用户登录后按角色授权。
- 服务 JWT：control-plane 签发（RS256，5 分钟，带 audience），提供 JWKS；`meridian-security` 提供校验与签发客户端，替换开发期的静态服务凭据。
- `meridian-security` 的 OIDC 令牌认证（公开 API 上需配合 `Meridian-Project` 头）与服务 JWT 认证。

### 不做

- knowledge 服务的服务 JWT 接入（T18；knowledge 在那时才创建）。
- 控制台的登录页面（T21）。

## 交付物

### 1. 本地 Keycloak

- Compose 的 `auth` profile 启动 Keycloak，导入一个开发 realm（控制台客户端用授权码加 PKCE，几个分别持有不同角色的测试用户）。realm 文件提交到仓库，不含真实凭据。
- 集成测试用 Keycloak 的 Testcontainers 模块。

### 2. 认证

- 公开 API 同时接受 API Key 与 OIDC 令牌；OIDC 令牌必须带 `Meridian-Project` 头，且用户在该项目中有对应角色。
- 管理 API 只接受 OIDC 令牌。
- T14a 的角色矩阵测试改为由 OIDC 令牌驱动，复用同一张表。

### 3. 服务 JWT

- control-plane 签发，RS256，有效期 5 分钟，带 audience；提供 JWKS 端点。
- 签发客户端缓存令牌，过期前刷新；校验端缓存 JWKS，遇到未知 `kid` 时刷新一次。
- 密钥对在开发环境自动生成并持久化到本地卷，生产环境从密钥管理系统加载（本任务实现开发方式，生产方式留扩展点并写进部署文档的待办）。

## 验收标准

- [T14b-A01] OIDC：Keycloak 登录得到的令牌可以访问管理 API；配合 `Meridian-Project` 头可以访问 runtime 公开 API；没有该项目角色时被拒绝。验证：集成测试。
- [T14b-A02] 服务 JWT：runtime 调用 control-plane 内部接口时使用服务 JWT；过期或 audience 不符时被拒绝；JWKS 轮换后校验仍然成功。验证：集成测试。
- [T14b-A03] 开发期静态服务凭据已从非测试代码中移除。验证：代码搜索输出。
- [T14b-A04] 角色矩阵测试由 OIDC 令牌驱动，全部通过。验证：测试报告。
- [T14b-A05] `scripts/dev/verify.sh` 通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 更新 `meridian-security`、`meridian-control-plane` 的 `CLAUDE.md`。
- 在根 `CLAUDE.md` 中加入启动 Keycloak（`auth` profile）的命令与测试用户说明。
