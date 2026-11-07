# T18 知识库写入

| 项 | 内容 |
|---|---|
| 阶段 | 3 M1 知识库 |
| 依赖 | T17b |
| 推荐配置 | Opus 5.5，effort high，新会话 |
| 规模 | 大 |
| 关联验证项 | 无 |

启动方式：在仓库根目录开新会话，输入 `/task T18`，或粘贴本文件全部内容。

## 执行要求

开工前完整阅读 `docs/plan/protocol.md` 并严格遵守，开工检查、范围与决策分级、质量门禁、验收记录、收尾与报告格式都以它为准。最重要的几条：

- 先读后写；AgentScope 只以 2.0.4 为准（读法见 `docs/plan/references.md`）。
- 只做本任务范围内的事；补充型决策自己定（影响多个模块或难以回退的才写 ADR），变更型决策写 ADR 提议后停止受影响部分。
- 不删改测试和验收项；只有亲自验证通过的验收项才能标 `passing`。
- 每完成一个检查项就更新 `PLAN.md` 检查点并提交；上下文会被自动压缩，不要提前停下。
- 不推送，不打印凭据。

## 背景与目标

知识库问答是业务方最常见的需求之一。知识库服务完全自研，不依赖任何智能体框架（ADR-0005）。本任务实现写入一侧：上传、解析、切分、Embedding、入库，包括处理扫描件与复杂 PDF 的 Python 解析服务。

切分质量直接决定检索效果。中文文档的结构（标题层级、表格、条款编号）要被保留下来。

## 必读

1. 架构设计「知识库架构」全节、「部署单元」中 knowledge 与 doc-parser 行、「数据架构」中 knowledge 部分。
2. `docs/engineering/coding-standards.md`「Python 子项目」。
3. `docs/engineering/testing.md`「真实模型测试」（Embedding 模型与端点）。
4. 已有模块 `CLAUDE.md`：`meridian-persistence`、`meridian-security`、`meridian-runtime`（内部 Embedding 接口）。

## 范围

### 要做

- `meridian-knowledge` 应用骨架（Spring Boot MVC 加虚拟线程）、`kb` schema 迁移。
- 知识库与文档的管理接口（写入 `meridian-v1.yaml` 的知识库部分）：创建知识库、上传文档、查询写入进度、重新处理、删除文档；文档带 `acl_tags`。
- 写入流水线：对象存储 → `ingestion_jobs` 队列（`SKIP LOCKED`，失败指数退避重试 3 次）→ 解析 → 切分 → 批量 Embedding → 入库。
- 解析：Office 与常规 PDF 用 Apache Tika、PDFBox、POI；扫描件、复杂表格、公式交给 `doc-parser`。
- `doc-parser/`：Python（uv、FastAPI），默认使用 Docling；MinerU 作为可选后端，按配置启用（它体积大、许可证需核对，写进 README）。接口：上传文件，返回带结构的 Markdown 与元数据（页码、标题路径）。Docling 需要的模型在镜像构建时下载并固化，运行时不联网；只启用 OCR 必需的模型；README 写明镜像体积与内存占用。构建需要代理时用 `--build-arg HTTPS_PROXY=http://http.docker.internal:3128`。内存不足以运行 doc-parser 时，依赖它的验收项标 `blocked` 并在报告开头提醒用户。
- 切分：按标题层级与段落结构，目标 512 token，重叠 64 token，表格整体保留；每个切片记录来源定位（文档、页码、标题路径）。
- Embedding：经 `meridian-model-client` 调用 runtime 的 `/internal/v1/embeddings`（服务 JWT），每批 64 条；用量只由 runtime 记账。
- `chunks` 表：`embedding vector(1024)`（维度来自所选 Embedding 模型，配置化）、HNSW 索引、`tsv`（中文配置 `chinese`）的 GIN 索引、`acl_tags` 的 GIN 索引。
- 写操作写审计 outbox；所有 POST 支持 `Idempotency-Key`（复用 `persistence.idempotency`）。
- 知识库存储计量：每日按租户、项目、知识库统计存储字节，经 knowledge 的 outbox `usage` 主题上报（架构设计「跨服务数据流」）。
- 服务 JWT：knowledge 调用 runtime 内部接口时使用；API Key 的吊销通知被 knowledge 订阅。
- 替换 T15b 留下的 `TODO(T18)`：`KnowledgeCatalogClient` 的真实实现（control-plane 发布智能体版本时校验知识库存在）。
- Docker Compose 的 `app` profile 加入 knowledge 与 doc-parser。

### 不做

- 检索、rerank、MCP Server、`knowledge_search` 的实现（T19）。
- 控制台页面（T25a）。

## 实现要点

- 解析结果统一转成带结构的中间表示（标题、段落、列表、表格、页码），切分器只依赖这个中间表示，不依赖具体解析器。
- 切分的 token 计数用与 Embedding 模型相近的分词方式估算，并在注释中写明误差。
- 准备一组小而真实的测试语料：一份带多级标题的中文制度文件（docx）、一份带表格的 PDF、一份扫描件 PDF、一份 Markdown，放在测试资源中（自己编写内容，不使用有版权的文档）。

## 验收标准

- [T18-A01] 四种测试语料都能完成写入，进度接口反映状态，失败的任务按退避重试。验证：集成测试。
- [T18-A02] 切分保留标题路径与页码；表格作为整体切片；切片长度落在目标范围内。验证：针对制度文件与 PDF 的断言。
- [T18-A03] 扫描件经 `doc-parser` 解析出文字（Docling）。验证：集成测试（容器化的 doc-parser）。
- [T18-A04] Embedding 经 runtime 内部接口调用，用量记在 runtime 一侧，knowledge 不产生用量记录。验证：集成测试；`-Plive` 下用真实模型写入一份文档。
- [T18-A05] `chunks` 的向量、全文、`acl_tags` 索引存在且被使用（`EXPLAIN` 确认）。验证：测试输出。
- [T18-A06] 删除文档后，切片与对象存储中的原文件都被删除。验证：集成测试。
- [T18-A07] `doc-parser` 的 Ruff、类型检查、pytest 通过，已接入 `scripts/dev/verify.sh`。验证：命令输出。
- [T18-A08] knowledge 调用 runtime 时使用服务 JWT；吊销的 API Key 在 1 秒内被 knowledge 拒绝。验证：集成测试。
- [T18-A09] 知识库存储量每日上报为用量；knowledge 的写操作有审计记录；POST 支持幂等。验证：集成测试。
- [T18-A10] `scripts/dev/verify.sh` 通过。验证：命令输出。

## 收尾

按 `docs/plan/protocol.md`「收尾」执行。另外：

- 创建 `meridian-knowledge`、`meridian-model-client`、`doc-parser` 的 `CLAUDE.md`。
- 在根 `CLAUDE.md` 中加入启动 knowledge 与 doc-parser 的命令。
