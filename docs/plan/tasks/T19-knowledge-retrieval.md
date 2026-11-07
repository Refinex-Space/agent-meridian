# T19 知识库检索与接入

| 项 | 内容 |
|---|---|
| 阶段 | 3 M1 知识库 |
| 依赖 | T18 |
| 推荐配置 | Opus 5.5，effort high，新会话 |
| 规模 | 中 |
| 关联验证项 | P8（定稿） |

启动方式：在仓库根目录开新会话，输入 `/task T19`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；AgentScope 只以 2.0.4 为准（读法见 `docs/plan/references.md`）。
- 只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

本任务实现知识库的检索一侧，以及它对外的四种形态：平台工具 `knowledge_search`、前置检索中间件、REST 接口、MCP Server。检索结果要带可靠的引用，且严格按终端用户的权限标签过滤：一个人不应该从智能体那里得到他无权查看的文档内容。

## 必读

1. 架构设计「知识库架构」中的「检索流程」与「对外形态」、「运行时架构」中「平台中间件」的 `KnowledgePrefetchMiddleware`、「平台工具」的 `knowledge_search`。
2. `docs/poc/P08-MCP-Server.md`（T10b 的预验证）。
3. 已有模块 `CLAUDE.md`：`meridian-knowledge`、`meridian-runtime`、`meridian-engine-agentscope`。

## 范围

### 要做

- 混合检索：向量检索（HNSW，取前 50）与全文检索（取前 50）并行；`tenant_id`、`knowledge_base_id IN (...)`、`acl_tags && :caller_tags` 全部下推到 SQL；RRF 融合；调用 runtime 的 `/internal/v1/rerank` 取前 k（默认 8）；返回内容、分数、来源定位。
- 可选的查询改写（小模型，经 runtime 内部接口，按智能体配置开关）。
- REST：`POST /v1/knowledge-bases/{id}/search`。
- MCP Server：`/mcp/knowledge`，MCP SDK 0.17.2 的 `HttpServletStreamableServerTransportProvider` 注册为 Servlet；API Key 认证；工具 `knowledge_search`。
- runtime：`KnowledgeSearchPort` 的 HTTP 实现（服务 JWT；`caller_tags` 来自会话的 `end_user_attributes.acl_tags`）；`knowledge_search` 工具接通；`KnowledgePrefetchMiddleware`（智能体配置为前置检索时，用最后一条用户消息检索并注入）。
- 检索质量冒烟测试。
- P8 定稿。

### 不做

- 控制台页面（T25a）；控制台所需的其他后端接口（T19b）。

## 交付物

### 1. 检索质量冒烟测试

- 测试语料：约 30 段自编的中文制度条款（差旅、报销、采购、信息安全等），带不同的 `acl_tags`。语料与问题一旦写定，不为了改善指标而修改。
- 20 个问题及其标准答案所在的切片。
- 指标：命中率@5、MRR；对比"仅向量""仅全文""混合""混合加 rerank"四种配置。结果写入 `docs/engineering/` 下的检索评测记录，并作为回归基线（低于基线时测试失败，基线值写在测试中并注明来源）。
- 用 `-Plive` 运行（真实 Embedding 与 rerank）。

### 2. 端到端问答

- 一个配置了知识库的智能体，用 `qwen-plus` 回答 5 个问题，回答中引用的来源与检索结果一致；无权限的终端用户问到受限内容时，智能体回答"找不到相关信息"一类的内容，且事件中不出现受限切片。

### 3. P8 定稿

- 用 MCP Inspector（`npx @modelcontextprotocol/inspector` 的 CLI 模式）调用 `/mcp/knowledge` 的 `knowledge_search`，记录命令与输出；补全 `docs/poc/P08-MCP-Server.md`。

## 实现要点

- 权限过滤只能在 SQL 中下推，不能"先取再筛"：后者会让结果数不稳定，并在日志或缓存中留下越权数据。
- RRF 的常数取常用值并在注释中写明来源；参数做成配置项，默认值与评测结果一致。
- 前置检索注入的内容包上来源标签，平台基础提示词已声明这类内容是数据而不是指令（架构设计「智能体安全」）。

## 验收标准

- [T19-A01] 混合检索的过滤条件全部在 SQL 中，`EXPLAIN` 确认使用了索引。验证：测试输出。
- [T19-A02] 权限隔离：没有对应标签的调用方在 REST、`knowledge_search`、MCP、前置检索四种形态下都检索不到受限切片。验证：集成测试。
- [T19-A03] 检索评测：四种配置的命中率@5 与 MRR 已记录；回归测试使用录制的 Embedding 与 rerank 结果（离线、确定），以本次实测值为基线。"混合加 rerank"不占优时写分析，**不调整语料与问题**。验证：`-Plive` 输出、评测记录与回归测试。
- [T19-A04] 端到端问答的 5 个问题引用的来源与检索结果一致；无权限用户的会话中，事件与工具结果里不出现受限切片（回答本身只断言非空）。验证：`-Plive` 测试。
- [T19-A05] P8：MCP Inspector 调通 `knowledge_search`。验证：命令与输出；报告定稿。
- [T19-A06] 前置检索按配置开关生效，注入内容带来源标签。验证：场景测试。
- [T19-A07] `scripts/dev/verify.sh` 通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 更新 `meridian-knowledge`、`meridian-runtime`、`meridian-engine-agentscope` 的 `CLAUDE.md`。
- 更新 `docs/poc/README.md` 中 P8 的状态。
