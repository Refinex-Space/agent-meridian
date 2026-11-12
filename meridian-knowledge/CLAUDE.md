# meridian-knowledge

知识库应用（Spring MVC + 虚拟线程，schema `kb`，端口 18083）：T18 起有知识库与文档的公开 API（`KnowledgeBases` 标签）、写入流水线（对象存储 → `ingestion_jobs` → 解析 → 切分 → 经 runtime 的 Embedding → `kb.chunks`）、给 control-plane 与 runtime 的内部接口、每日存储计量（ADR-0051）。检索、rerank、MCP Server 在 T19。不依赖任何智能体框架，不直接调用模型供应商。

## 命令

- 编译与单测：`./mvnw -pl meridian-knowledge -am test`（`ChunkerTest`、`IngestionDomainTest`、`DocumentDomainTest`、`ParsersTest` 用 `src/test/resources/corpus/` 的语料）
- 集成测试：`./mvnw -pl meridian-knowledge -am verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false`（Testcontainers 的 PostgreSQL、Redis、MinIO 与容器化的 doc-parser；首次构建 doc-parser 镜像约 10 分钟，表格 PDF 在 CPU 上要几分钟）；单个再加 `-Dit.test=<类名> -Dfailsafe.failIfNoSpecifiedTests=false`
- 真实模型：`./mvnw -Plive -pl meridian-knowledge -am verify`（`IngestionLiveIT`，runtime 替身把 Embedding 转给 DashScope，约 2 次调用，并打印切分估算与真实用量的偏差）
- 本地启动：`scripts/dev/up.sh`、`run-control-plane.sh`、`run-runtime.sh --server.port=18080`、`run-doc-parser.sh` 后 `scripts/dev/run-knowledge.sh`；密钥取 env 文件的 `MERIDIAN_KNOWLEDGE_CLIENT_SECRET`
- 迁移：`src/main/resources/db/migration/kb/`（`V0001` 知识库、文档、作业、切片、`storage_reports`、outbox、幂等键）；向量维度是 Flyway 占位符 `embedding_dimensions`（`KnowledgeConfiguration` 按 `meridian.knowledge.embedding.dimensions` 注入）

## 包地图

- `knowledgebase`：`app.KnowledgeBaseService`；`api.KnowledgeBasesApiController` 实现整个 `KnowledgeBases` 标签（文档的操作交给 `document.app.DocumentService`），`api.InternalKnowledgeApiController`（只接受 control-plane、runtime）；`infra.system.KnowledgeBaseLookupRepository` 是内部接口的跨租户查找。
- `document`：`app.DocumentService`（上传：Tika 按内容检测类型 → 对象存储 `kb/{tenant}/{project}/{kb}/{doc}` → 同一事务插入文档、作业与审计，提交后唤醒 Worker；重新处理；删除后删对象）；`domain.DocumentFormat` 决定解析器，`AclTags` 解析逗号分隔的标签。
- `ingestion`：`app.IngestionWorker`（轮询：收回过期租约 → 认领；每个作业一个虚拟线程；续租）、`app.IngestionPipeline`（一次尝试：每阶段以 `(作业, attempts)` 记进度，结束事务锁住文档与作业再替换切片）、`app.DocumentParsing`（路由）；`domain`：中间表示 `Block`、`Chunker`、`MarkdownStructure`、`RetrySchedule`、`IngestionFailure`、`PdfInspection`；`infra`：`TikaOfficeParser`、`PdfBoxParser`（扫描件与框线检查、按字号推断标题）、`DocParserClient`、`RuntimeEmbeddings`、`ChunkRepository`；`infra.system.IngestionQueueRepository`（认领、收回、结束，同时推进文档状态）。
- `metering.app.StorageMetering`（每日 `knowledge_storage_gb_days`）、`audit.app.KnowledgeAudit`、`web`（`PublicRequests` 幂等、`UploadLimitAdvice`、`ApiKeyRevocationListener`）、`config.KnowledgeConfiguration`。

## 不变量

- 写入结果只在结束事务里落地：`lockForFinish` 锁住本次尝试（`status = 'running' AND attempts = :attempt`）与文档行，删除旧切片、插入新切片、改文档；锁不到（被收回、文档已删）就丢弃结果。进度写入同样带尝试次数（ADR-0051）。
- 可重试（依赖不可用、429、5xx、租约过期）最多 4 次尝试，间隔 `backoff-base × 2^(n-1)`；不可重试（类型、损坏、`model.not_found`、维度不符）直接 `failed`。每份文档同时只有一个未结束的作业（部分唯一索引）。
- 模型用量只在 runtime 记账：knowledge 的 outbox `usage` 只有存储计量；Embedding 每批至多 64 条，输入是"标题路径 + 正文"，带 `dimensions`。
- 公开 API 经 `ApiKeyFilter` 认证，读 `knowledge:read`、写 `knowledge:write`；所有 POST 经 `PublicRequests` 支持 `Idempotency-Key`（作用域是 `PlatformContext.actor()`）；写操作在业务事务内写审计。

## 易错点

- `kb.chunks.embedding` 的维度在迁移时定下，启动时与配置核对，改配置要新迁移。pgvector 类型、运算符类与 `chinese` 配置在 `public`：迁移写 `public.vector`，SQL 写 `'public.chinese'`。
- 生成的上传接口把 `acl_tags` 当 `@RequestParam`：规范里它是逗号分隔的字符串（OpenAPI 的数组表单字段 Spring 解析不了）。
- Word 的标题样式由 Tika 映射为 `h1`–`h6`（"标题"样式也是 `h1`）；文档中有意的空格要保留（只合并连续空白）。PDF 段落按"行距超过正文常见行距 1.3 倍或上一行提前结束"切分。
- 测试共用一个 Spring 上下文与库：用新的租户隔离，`StubPlatform.failNext` 是全局的，用完即止；存储计量在测试里手动执行。

## 对外接口

- 公开 API：`/v1/knowledge-bases*`（8 个操作，`meridian-v1.yaml`）。内部接口：`GET /internal/v1/knowledge-bases/{id}`（control-plane 发布智能体版本时校验，runtime 在 T19 用）。
- 调用：control-plane 的 `api-keys:verify`、`project-access:resolve`（经 `security`），runtime 的 `/internal/v1/embeddings`（经 `meridian-model-client`），doc-parser 的 `POST /v1/parse`。outbox：`audit`（`knowledge_base.created`、`document.uploaded`、`document.reprocessed`、`document.deleted`）、`usage`（`knowledge_storage_gb_days`，样本在 `meridian-test-support`）。订阅 `key.revoked`。
