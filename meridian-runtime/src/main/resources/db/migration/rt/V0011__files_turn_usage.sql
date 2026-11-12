-- 文件、工作区 API 与用量事件（T17b，ADR-0050）：上传的文件、每轮每个模型的用量累计、工作区条目的内容大小。

-- ---------------------------------------------------------------------------------------------
-- 上传的文件：项目级，内容在对象存储
-- ---------------------------------------------------------------------------------------------
CREATE TABLE files (
    id         text        PRIMARY KEY,
    tenant_id  text        NOT NULL,
    project_id text        NOT NULL,
    filename   text        NOT NULL,
    media_type text        NOT NULL,
    size_bytes bigint      NOT NULL CHECK (size_bytes > 0),
    sha256     text        NOT NULL,
    object_key text        NOT NULL,
    created_by text        NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE INDEX files_project_idx ON files (tenant_id, project_id, id);

COMMENT ON TABLE files IS '上传的文件（POST /v1/files）：属于项目，在消息中按 ID 引用，轮次开始时放进会话工作区（ADR-0050）';
COMMENT ON COLUMN files.id IS '文件 ID（fil_…）';
COMMENT ON COLUMN files.tenant_id IS '租户 ID';
COMMENT ON COLUMN files.project_id IS '项目 ID';
COMMENT ON COLUMN files.filename IS '规范化后的文件名：不含目录部分与控制字符，不超过 255 字节';
COMMENT ON COLUMN files.media_type IS '服务端按内容检测的媒体类型（Apache Tika），不采用客户端声明的类型';
COMMENT ON COLUMN files.size_bytes IS '字节数';
COMMENT ON COLUMN files.sha256 IS '内容的 SHA-256（小写十六进制）';
COMMENT ON COLUMN files.object_key IS '对象存储的键：rt/{tenant}/{project}/files/{file_id}';
COMMENT ON COLUMN files.created_by IS '上传者：API Key ID 或控制台用户（PlatformContext.actor()）';
COMMENT ON COLUMN files.created_at IS '上传时间';

-- ---------------------------------------------------------------------------------------------
-- 每轮每个模型的用量累计：与 outbox 的 usage 消息同一事务累加，session.usage 据此给出本轮与会话累计
-- ---------------------------------------------------------------------------------------------
CREATE TABLE turn_usage (
    turn_id            text        NOT NULL,
    model_id           text        NOT NULL,
    tenant_id          text        NOT NULL,
    project_id         text        NOT NULL,
    -- 不建会话外键：结算可能与会话清理并发，用量记录（outbox）不能因此写入失败；清理时按 session_id 删除
    session_id         text        NOT NULL,
    calls              integer     NOT NULL CHECK (calls >= 1),
    input_tokens       bigint      NOT NULL CHECK (input_tokens >= 0),
    output_tokens      bigint      NOT NULL CHECK (output_tokens >= 0),
    cache_read_tokens  bigint      NOT NULL CHECK (cache_read_tokens >= 0),
    cache_write_tokens bigint      NOT NULL CHECK (cache_write_tokens >= 0),
    reasoning_tokens   bigint      NOT NULL CHECK (reasoning_tokens >= 0),
    updated_at         timestamptz NOT NULL,
    PRIMARY KEY (turn_id, model_id)
);

-- 会话累计与会话清理
CREATE INDEX turn_usage_session_idx ON turn_usage (session_id);

COMMENT ON TABLE turn_usage IS '每轮每个模型的用量累计（model_tokens：推理与压缩，含子线程），与 outbox 的 usage 消息同一事务累加（ADR-0050）';
COMMENT ON COLUMN turn_usage.turn_id IS '轮次 ID；子线程的调用记在父轮次下';
COMMENT ON COLUMN turn_usage.model_id IS '实际调用的模型 ID（降级时为备用模型）';
COMMENT ON COLUMN turn_usage.tenant_id IS '租户 ID';
COMMENT ON COLUMN turn_usage.project_id IS '项目 ID';
COMMENT ON COLUMN turn_usage.session_id IS '会话 ID';
COMMENT ON COLUMN turn_usage.calls IS '结算的调用次数';
COMMENT ON COLUMN turn_usage.input_tokens IS '输入 token';
COMMENT ON COLUMN turn_usage.output_tokens IS '输出 token';
COMMENT ON COLUMN turn_usage.cache_read_tokens IS '命中提示词缓存的输入 token';
COMMENT ON COLUMN turn_usage.cache_write_tokens IS '写入提示词缓存的输入 token';
COMMENT ON COLUMN turn_usage.reasoning_tokens IS '推理用的输出 token';
COMMENT ON COLUMN turn_usage.updated_at IS '最近一次累加的时间';

-- ---------------------------------------------------------------------------------------------
-- 工作区条目的内容大小：工作区 API 列表用，不必读出内容
-- ---------------------------------------------------------------------------------------------
ALTER TABLE workspace_entries ADD COLUMN content_size bigint CHECK (content_size >= 0);

COMMENT ON COLUMN workspace_entries.content_size IS
    '文件内容的字节数（值是 FileData 时由写入方计算）；为空表示不是文件或 T17b 之前写入';
