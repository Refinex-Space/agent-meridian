# T15a 凭据库、供应商、模型目录与价格表

| 项 | 内容 |
|---|---|
| 阶段 | 2 M1 控制面与治理 |
| 依赖 | T14b |
| 推荐配置 | Opus 5.5，effort high，新会话 |
| 规模 | 大 |
| 关联验证项 | 无 |

启动方式：在仓库根目录开新会话，输入 `/task T15a`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；AgentScope 只以 2.0.4 为准（读法见 `docs/plan/references.md`）。
- 只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

runtime 目前从 `dev-config/providers/` 读取供应商配置，从环境变量读取凭据。本任务在 control-plane 中建立凭据库（信封加密）、供应商账号、模型目录与价格表，并让 runtime 的模型调用改从 control-plane 解析供应商与凭据。智能体、环境、技能、MCP 的管理在 T15b。

凭据库是整个平台安全要求最高的组件：明文只能在 runtime 的内存里短暂存在，任何接口都读不出来。

## 必读

1. 架构设计：「数据架构」（control-plane 部分、跨服务数据流）、「安全架构」中的「凭据」、「模型调用」、「计量与预算」中的价格表。
2. 已有模块 `CLAUDE.md`：`meridian-control-plane`、`meridian-runtime`。
3. runtime 中 `SecretResolver`、`ModelGateway` 的实现与 `dev-config/providers/`。

## 范围

### 要做

- control-plane 的数据模型与管理 API：`secrets`、`provider_accounts`、`models`（上下文窗口、能力、价格表关联）、`price_books`（有版本）。
- 凭据库：信封加密；`KeyEncryptionService` 抽象，提供本地主密钥实现（开发）与 HashiCorp Vault Transit 实现；内部接口 `/internal/v1/secrets:resolve`（服务 JWT，每次访问写审计）。
- 内部接口：供应商账号与模型的解析。
- 失效通知：供应商、模型、凭据变更时在 Pub/Sub `config.invalidate` 发布。
- runtime 侧：基于 control-plane 的 `SecretResolver` 与供应商解析，带缓存与失效；文件实现只保留在测试与开发 profile。

### 不做

- 智能体、版本、环境、技能、MCP、权限策略（T15b）。
- 预算与价格的计费应用（T16）、控制台页面（T23）。

## 交付物

### 1. 凭据

- 主密钥只存在于密钥管理系统（开发环境为本地文件，路径由配置给出，不提交）；数据密钥按租户区分；密文存 `cp.secrets`。
- 管理 API 只能写入、轮换、删除凭据，**不能**读出明文；列表只显示名称、创建时间、最后使用时间、被哪些资源引用。
- runtime 解析出的明文只在内存中缓存 5 分钟。
- 解析凭据的仓储按租户隔离；内部解析接口校验调用方的服务 JWT 与请求中的租户一致。

### 2. 供应商与模型

- 供应商账号：类型（OpenAI 兼容、Anthropic、Gemini，以 T09a 实现的类型为准）、端点、凭据引用、限流参数（RPM、TPM）。
- 模型：所属供应商账号、上下文窗口、能力（工具调用、视觉、推理）、备用模型、价格表关联。
- 价格表：有版本，按输入、输出、缓存读、缓存写、推理分别定价；新版本生效后，旧用量记录仍按记录时的版本结算。
- 删除被引用的供应商账号或模型时返回冲突，并列出引用方。

## 实现要点

- runtime 的缓存失效要在收到通知后立即生效；同时保留一个较短的过期时间作为兜底（通知丢失时）。
- 凭据明文不进入任何日志、事件、异常消息、指标标签；写一个测试在一次完整会话后扫描日志与事件，确认没有明文。

## 验收标准

- [T15a-A01] 凭据：明文无法经任何接口读出；密文存储；不同租户的数据密钥不同；每次解析都有审计记录；日志与事件中无明文。验证：集成测试与日志检查。
- [T15a-A02] Vault Transit 实现通过 Vault 的 Testcontainers 测试。验证：测试。
- [T15a-A03] 通过管理 API 创建凭据、供应商账号与模型后，runtime 的模型调用从 control-plane 解析供应商与凭据（`scripted` 供应商与 `-Plive` 的 DashScope 各一次），不读 `dev-config/providers/`。验证：集成测试与 `-Plive` 输出。
- [T15a-A04] 价格表版本化：新版本生效后，新用量按新价格，旧记录不变。验证：集成测试。
- [T15a-A05] 供应商、模型、凭据变更后 runtime 立即使用新配置；删除被引用的资源返回冲突。验证：集成测试。
- [T15a-A06] `scripts/dev/verify.sh` 通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 更新 `meridian-control-plane`、`meridian-runtime` 的 `CLAUDE.md`。
