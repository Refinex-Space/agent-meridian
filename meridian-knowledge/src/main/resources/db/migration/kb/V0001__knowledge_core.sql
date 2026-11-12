-- knowledge 的第一个迁移（架构设计「数据架构」knowledge 部分、「知识库架构」，T18，ADR-0051）：知识库、文档、写入作业、切片、
-- 存储计量的去重表，以及 outbox 与幂等键。向量维度取 Flyway 占位符 embedding_dimensions（meridian.knowledge.embedding.dimensions，
-- 默认 1024）；pgvector 的类型与运算符类、zhparser 的 chinese 配置都在 public schema（deploy/docker/postgres/initdb）。

-- outbox 表：业务事务内写入、由 OutboxRelay 投递到 Redis Stream 的消息（架构设计「跨服务数据流」）。
-- 使用 outbox 的服务把本片段复制到自己 schema 的第一个迁移中；表建在 Flyway 的默认 schema（cp、rt 或 kb）。
CREATE TABLE outbox (
    id           text        PRIMARY KEY,
    topic        text        NOT NULL,
    payload      jsonb       NOT NULL,
    created_at   timestamptz NOT NULL,
    published_at timestamptz,
    claimed_until timestamptz,
    attempts     integer     NOT NULL DEFAULT 0
);

-- Relay 只扫描未发布的消息，按 id（ULID，大致按时间）顺序取
CREATE INDEX outbox_unpublished_idx ON outbox (id) WHERE published_at IS NULL;

COMMENT ON TABLE outbox IS '待可靠投递的消息：业务事务内写入，OutboxRelay 发布到 Redis Stream 后标记';
COMMENT ON COLUMN outbox.id IS '消息 ID（ULID），消费方据此幂等';
COMMENT ON COLUMN outbox.topic IS '主题，即目标 Redis Stream 的键，例如 usage、audit';
COMMENT ON COLUMN outbox.payload IS '消息载荷（JSON，字段 snake_case），自带租户与项目';
COMMENT ON COLUMN outbox.created_at IS '写入时间';
COMMENT ON COLUMN outbox.published_at IS '发布时间；为空表示尚未发布';
COMMENT ON COLUMN outbox.claimed_until IS 'Relay 的认领截止时间：认领后在事务外发布，过期未标记的由任一实例重新认领（ADR-0048）';
COMMENT ON COLUMN outbox.attempts IS '发布失败的次数；达到上限后不再自动投递，等人工处理';

-- 幂等键表：HTTP 请求头 Idempotency-Key 的首次结果，保留 24 小时（架构设计「API 设计」中的「约定」）。
-- 每个对外提供 POST 接口的服务把本片段复制到自己 schema 的迁移中；表建在 Flyway 的默认 schema。
CREATE TABLE idempotency_keys (
    tenant_id       text        NOT NULL,
    principal       text        NOT NULL,
    idempotency_key text        NOT NULL,
    request_hash    text        NOT NULL,
    status          text        NOT NULL CHECK (status IN ('in_progress', 'completed')),
    response_status integer,
    response_body   text,
    created_at      timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, principal, idempotency_key)
);

-- 过期清理按 expires_at 扫描
CREATE INDEX idempotency_keys_expires_idx ON idempotency_keys (expires_at);

COMMENT ON TABLE idempotency_keys IS 'Idempotency-Key 的首次请求与结果，24 小时内同键同请求体返回首次结果';
COMMENT ON COLUMN idempotency_keys.principal IS '调用方身份，例如 API Key ID；同一租户内不同调用方的同名键互不影响';
COMMENT ON COLUMN idempotency_keys.idempotency_key IS '调用方提供的 Idempotency-Key 请求头';
COMMENT ON COLUMN idempotency_keys.request_hash IS '请求指纹（方法、路径、请求体的 SHA-256），同键不同指纹视为冲突';
COMMENT ON COLUMN idempotency_keys.status IS 'in_progress：首次请求处理中；completed：已保存结果';
COMMENT ON COLUMN idempotency_keys.response_status IS '首次请求的 HTTP 状态码，completed 时有值';
COMMENT ON COLUMN idempotency_keys.response_body IS '首次请求的响应体，completed 时有值，可为空';
COMMENT ON COLUMN idempotency_keys.created_at IS '首次请求开始处理的时间';
COMMENT ON COLUMN idempotency_keys.expires_at IS '过期时间，过期后同一个键可以重新使用';

-- ---------------------------------------------------------------------------------------------
-- 知识库
-- ---------------------------------------------------------------------------------------------
CREATE TABLE knowledge_bases (
    id              text        PRIMARY KEY,
    tenant_id       text        NOT NULL,
    project_id      text        NOT NULL,
    name            text        NOT NULL,
    description     text,
    embedding_model text        NOT NULL,
    created_by      text        NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL
);

CREATE INDEX knowledge_bases_project_idx ON knowledge_bases (tenant_id, project_id, id);

COMMENT ON TABLE knowledge_bases IS '知识库，属于一个项目';
COMMENT ON COLUMN knowledge_bases.id IS '知识库 ID（kb_…）';
COMMENT ON COLUMN knowledge_bases.name IS '名称（不要求唯一）';
COMMENT ON COLUMN knowledge_bases.description IS '说明；可为空';
COMMENT ON COLUMN knowledge_bases.embedding_model IS '写入与检索使用的 Embedding 模型（租户目录中的 mdl_…），创建后不可改';
COMMENT ON COLUMN knowledge_bases.created_by IS '创建者：API Key ID，或 user: 加控制台用户的主体标识';

-- ---------------------------------------------------------------------------------------------
-- 文档
-- ---------------------------------------------------------------------------------------------
CREATE TABLE documents (
    id                text        PRIMARY KEY,
    tenant_id         text        NOT NULL,
    project_id        text        NOT NULL,
    knowledge_base_id text        NOT NULL REFERENCES knowledge_bases (id) ON DELETE CASCADE,
    filename          text        NOT NULL,
    media_type        text        NOT NULL,
    size_bytes        bigint      NOT NULL CHECK (size_bytes > 0),
    sha256            text        NOT NULL,
    object_key        text        NOT NULL,
    acl_tags          text[]      NOT NULL DEFAULT '{}',
    status            text        NOT NULL CHECK (status IN ('queued', 'processing', 'ready', 'failed')),
    parser            text,
    chunk_count       integer     NOT NULL DEFAULT 0 CHECK (chunk_count >= 0),
    index_bytes       bigint      NOT NULL DEFAULT 0 CHECK (index_bytes >= 0),
    created_by        text        NOT NULL,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL
);

CREATE INDEX documents_knowledge_base_idx ON documents (tenant_id, project_id, knowledge_base_id, id);

COMMENT ON TABLE documents IS '知识库中的文档：原文件在对象存储 kb/{tenant}/{project}/{knowledge_base}/{document}';
COMMENT ON COLUMN documents.id IS '文档 ID（doc_…）';
COMMENT ON COLUMN documents.filename IS '规范化后的文件名';
COMMENT ON COLUMN documents.media_type IS '服务端按内容检测的媒体类型';
COMMENT ON COLUMN documents.size_bytes IS '原文件的字节数';
COMMENT ON COLUMN documents.sha256 IS '原文件的 SHA-256（小写十六进制）';
COMMENT ON COLUMN documents.object_key IS '原文件在对象存储中的键';
COMMENT ON COLUMN documents.acl_tags IS '访问标签，写入时复制到每个切片';
COMMENT ON COLUMN documents.status IS 'queued 等待写入（含等待重试）、processing 写入中、ready 可检索、failed 最近一次写入失败';
COMMENT ON COLUMN documents.parser IS '最近一次成功解析所用的解析器：markdown、text、tika、pdfbox、doc-parser；没有成功过时为空';
COMMENT ON COLUMN documents.chunk_count IS '当前可检索的切片数';
COMMENT ON COLUMN documents.index_bytes IS '当前切片的正文（UTF-8）与向量（每维 4 字节）字节数之和，存储计量用';
COMMENT ON COLUMN documents.created_by IS '上传者：API Key ID，或 user: 加控制台用户的主体标识';

-- ---------------------------------------------------------------------------------------------
-- 写入作业：PostgreSQL 队列（SKIP LOCKED），每次上传、重新处理一个
-- ---------------------------------------------------------------------------------------------
CREATE TABLE ingestion_jobs (
    id               text        PRIMARY KEY,
    tenant_id        text        NOT NULL,
    project_id       text        NOT NULL,
    document_id      text        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    status           text        NOT NULL CHECK (status IN ('queued', 'running', 'succeeded', 'failed')),
    attempts         integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts     integer     NOT NULL CHECK (max_attempts >= 1),
    not_before       timestamptz NOT NULL,
    lease_owner      text,
    lease_expires_at timestamptz,
    stage            text        CHECK (stage IN ('parsing', 'chunking', 'embedding', 'storing')),
    chunks_total     integer,
    chunks_embedded  integer,
    error_code       text,
    error_message    text,
    error_retryable  boolean,
    started_at       timestamptz,
    finished_at      timestamptz,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL
);

-- 每份文档同时最多一个未结束的作业：重新处理与上传不会并发写同一份文档
CREATE UNIQUE INDEX ingestion_jobs_active_document_idx ON ingestion_jobs (document_id)
    WHERE status IN ('queued', 'running');
CREATE INDEX ingestion_jobs_queued_idx ON ingestion_jobs (not_before) WHERE status = 'queued';
CREATE INDEX ingestion_jobs_running_idx ON ingestion_jobs (lease_expires_at) WHERE status = 'running';
CREATE INDEX ingestion_jobs_document_idx ON ingestion_jobs (document_id, created_at);

COMMENT ON TABLE ingestion_jobs IS '文档的写入作业（架构设计「写入流程」）：认领时写租约，可重试的失败按指数退避重试 3 次（ADR-0051）';
COMMENT ON COLUMN ingestion_jobs.id IS '作业 ID（ijb_…）';
COMMENT ON COLUMN ingestion_jobs.status IS 'queued 等待（含等待重试）、running 执行中、succeeded 成功、failed 最终失败';
COMMENT ON COLUMN ingestion_jobs.attempts IS '已开始的尝试次数；最终写入以它做 fencing';
COMMENT ON COLUMN ingestion_jobs.max_attempts IS '最多尝试次数（首次加 3 次重试）';
COMMENT ON COLUMN ingestion_jobs.not_before IS '最早可被认领的时间：退避重试时推后';
COMMENT ON COLUMN ingestion_jobs.lease_owner IS '持有租约的 knowledge 节点';
COMMENT ON COLUMN ingestion_jobs.lease_expires_at IS '租约到期时间：执行中定期续租，过期后由下一次认领收回并计为一次失败';
COMMENT ON COLUMN ingestion_jobs.stage IS '正在进行的阶段：parsing、chunking、embedding、storing；未开始或已结束时为空';
COMMENT ON COLUMN ingestion_jobs.chunks_total IS '本次切分得到的切片数；切分之前为空';
COMMENT ON COLUMN ingestion_jobs.chunks_embedded IS '已计算向量的切片数；切分之前为空';
COMMENT ON COLUMN ingestion_jobs.error_code IS '最近一次失败的错误码';
COMMENT ON COLUMN ingestion_jobs.error_message IS '最近一次失败的说明（不含凭据与文档内容）';
COMMENT ON COLUMN ingestion_jobs.error_retryable IS '最近一次失败是否可重试';
COMMENT ON COLUMN ingestion_jobs.started_at IS '最近一次尝试开始的时间';
COMMENT ON COLUMN ingestion_jobs.finished_at IS '成功或最终失败的时间';

-- ---------------------------------------------------------------------------------------------
-- 切片：向量（HNSW）、全文（zhparser 的 chinese 配置，GIN）、访问标签（GIN）
-- ---------------------------------------------------------------------------------------------
CREATE TABLE chunks (
    id                text                                 PRIMARY KEY,
    tenant_id         text                                 NOT NULL,
    project_id        text                                 NOT NULL,
    knowledge_base_id text                                 NOT NULL,
    document_id       text                                 NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    seq               integer                              NOT NULL CHECK (seq >= 0),
    kind              text                                 NOT NULL CHECK (kind IN ('text', 'table')),
    content           text                                 NOT NULL,
    token_count       integer                              NOT NULL CHECK (token_count >= 0),
    embedding         public.vector(${embedding_dimensions}) NOT NULL,
    tsv               tsvector                             NOT NULL,
    acl_tags          text[]                               NOT NULL DEFAULT '{}',
    source_locator    jsonb                                NOT NULL,
    created_at        timestamptz                          NOT NULL,
    UNIQUE (document_id, seq)
);

CREATE INDEX chunks_embedding_hnsw_idx ON chunks USING hnsw (embedding public.vector_cosine_ops);
CREATE INDEX chunks_tsv_gin_idx ON chunks USING gin (tsv);
CREATE INDEX chunks_acl_tags_gin_idx ON chunks USING gin (acl_tags);
CREATE INDEX chunks_knowledge_base_idx ON chunks (tenant_id, knowledge_base_id);

COMMENT ON TABLE chunks IS '知识库切片：检索的单位（架构设计「检索流程」），一份文档的切片在一个事务中整体替换';
COMMENT ON COLUMN chunks.id IS '切片 ID（chk_…）';
COMMENT ON COLUMN chunks.knowledge_base_id IS '所属知识库（冗余，检索按它过滤）';
COMMENT ON COLUMN chunks.seq IS '在文档中的顺序，从 0 开始';
COMMENT ON COLUMN chunks.kind IS 'text 正文，table 表格（整体保留，过大时按行拆分并重复表头）';
COMMENT ON COLUMN chunks.content IS '正文（表格为 Markdown），不含标题路径';
COMMENT ON COLUMN chunks.token_count IS '正文的 token 估算（TextTokens.approximate）';
COMMENT ON COLUMN chunks.embedding IS '“标题路径 + 正文”的向量，维度取 meridian.knowledge.embedding.dimensions';
COMMENT ON COLUMN chunks.tsv IS '“标题路径 + 正文”的全文索引向量（public.chinese，zhparser）';
COMMENT ON COLUMN chunks.acl_tags IS '访问标签，取自文档；检索时 acl_tags && 调用方标签';
COMMENT ON COLUMN chunks.source_locator IS '来源定位：document_id、filename、page_start、page_end（没有页码时为 null）、heading_path';

-- ---------------------------------------------------------------------------------------------
-- 存储计量的去重：每个知识库每天一行，与 outbox 的 usage 消息同一事务写入
-- ---------------------------------------------------------------------------------------------
CREATE TABLE storage_reports (
    knowledge_base_id text        NOT NULL REFERENCES knowledge_bases (id) ON DELETE CASCADE,
    day               date        NOT NULL,
    tenant_id         text        NOT NULL,
    project_id        text        NOT NULL,
    file_bytes        bigint      NOT NULL CHECK (file_bytes >= 0),
    index_bytes       bigint      NOT NULL CHECK (index_bytes >= 0),
    reported_at       timestamptz NOT NULL,
    PRIMARY KEY (knowledge_base_id, day)
);

COMMENT ON TABLE storage_reports IS '已上报的知识库存储量（ADR-0051）：同一知识库同一天（UTC）只上报一次';
COMMENT ON COLUMN storage_reports.day IS '计量的日期（UTC）';
COMMENT ON COLUMN storage_reports.file_bytes IS '原文件字节数之和';
COMMENT ON COLUMN storage_reports.index_bytes IS '切片正文与向量字节数之和';
COMMENT ON COLUMN storage_reports.reported_at IS '上报（写 outbox）的时间';
