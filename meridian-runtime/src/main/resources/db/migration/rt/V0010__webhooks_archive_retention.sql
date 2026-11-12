-- 数据生命周期（T17a，ADR-0049）：Webhook 的 outbox 与投递记录，事件归档的覆盖范围与保留清理的进度，会话清理的扫描索引。

-- ---------------------------------------------------------------------------------------------
-- Webhook 的 outbox：订阅的四种事件与事件同一事务写入，投递器按项目的订阅扇出后删除
-- ---------------------------------------------------------------------------------------------
CREATE TABLE webhook_events (
    event_id   text        PRIMARY KEY,
    tenant_id  text        NOT NULL,
    project_id text        NOT NULL,
    session_id text        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    event_type text        NOT NULL,
    payload    text        NOT NULL,
    created_at timestamptz NOT NULL
);

-- 会话清理判断该会话是否还有未扇出的事件
CREATE INDEX webhook_events_session_idx ON webhook_events (session_id);

COMMENT ON TABLE webhook_events IS 'Webhook 的 outbox：session.status_idle、status_requires_action、error、status_terminated 与事件同一事务写入（只含主线程与会话级的事件），投递器扇出为投递记录后删除';
COMMENT ON COLUMN webhook_events.event_id IS '事件 ID（evt_…）';
COMMENT ON COLUMN webhook_events.tenant_id IS '租户 ID';
COMMENT ON COLUMN webhook_events.project_id IS '项目 ID，扇出时按它读取订阅';
COMMENT ON COLUMN webhook_events.session_id IS '所属会话';
COMMENT ON COLUMN webhook_events.event_type IS '事件类型';
COMMENT ON COLUMN webhook_events.payload IS '事件信封的 JSON 原文，即投递的请求体（签名按原文计算，用 text 而不是 jsonb）';
COMMENT ON COLUMN webhook_events.created_at IS '写入时间';

-- ---------------------------------------------------------------------------------------------
-- Webhook 的投递记录：每个 Webhook 每个事件一条，手动重投另生成一条
-- ---------------------------------------------------------------------------------------------
CREATE TABLE webhook_deliveries (
    id                   text        PRIMARY KEY,
    tenant_id            text        NOT NULL,
    project_id           text        NOT NULL,
    webhook_id           text        NOT NULL,
    session_id           text        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    event_id             text        NOT NULL,
    event_type           text        NOT NULL,
    payload              text        NOT NULL,
    status               text        NOT NULL CHECK (status IN ('pending', 'succeeded', 'failed')),
    attempts             integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at      timestamptz,
    deadline_at          timestamptz NOT NULL,
    claimed_until        timestamptz,
    last_attempt_at      timestamptz,
    last_response_status integer,
    last_error           text,
    redelivery_of        text,
    created_at           timestamptz NOT NULL,
    completed_at         timestamptz,
    -- 待投递的记录一定有下一次尝试的时间，结束的记录没有
    CHECK ((status = 'pending') = (next_attempt_at IS NOT NULL))
);

-- 投递器认领到期的记录
CREATE INDEX webhook_deliveries_due_idx ON webhook_deliveries (next_attempt_at) WHERE status = 'pending';
-- 公开 API 按项目列出，新的在前
CREATE INDEX webhook_deliveries_project_idx ON webhook_deliveries (tenant_id, project_id, id);
-- 会话清理判断该会话是否还有待投递的记录
CREATE INDEX webhook_deliveries_session_idx ON webhook_deliveries (session_id) WHERE status = 'pending';
-- 保留期清理
CREATE INDEX webhook_deliveries_created_idx ON webhook_deliveries (created_at);

COMMENT ON TABLE webhook_deliveries IS 'Webhook 的投递记录：失败按退避重试，创建后 24 小时内最多 8 次，保留 30 天（ADR-0049）';
COMMENT ON COLUMN webhook_deliveries.id IS '投递记录 ID（whd_…）';
COMMENT ON COLUMN webhook_deliveries.tenant_id IS '租户 ID';
COMMENT ON COLUMN webhook_deliveries.project_id IS '项目 ID';
COMMENT ON COLUMN webhook_deliveries.webhook_id IS 'Webhook ID（whk_…，配置在 cp.webhooks，跨 schema 不建外键）；每次投递按它读取当前的地址与密钥';
COMMENT ON COLUMN webhook_deliveries.session_id IS '事件所属的会话';
COMMENT ON COLUMN webhook_deliveries.event_id IS '事件 ID，接收方据此去重';
COMMENT ON COLUMN webhook_deliveries.event_type IS '事件类型';
COMMENT ON COLUMN webhook_deliveries.payload IS '请求体：事件信封的 JSON 原文';
COMMENT ON COLUMN webhook_deliveries.status IS 'pending 等待投递或重试，succeeded 收到 2xx，failed 重试用尽、过了截止时间或 Webhook 已删除、停用';
COMMENT ON COLUMN webhook_deliveries.attempts IS '已尝试的次数';
COMMENT ON COLUMN webhook_deliveries.next_attempt_at IS '下一次尝试的时间；只有 pending 有值';
COMMENT ON COLUMN webhook_deliveries.deadline_at IS '截止时间：创建后 24 小时，之后不再重试';
COMMENT ON COLUMN webhook_deliveries.claimed_until IS '投递器的认领截止时间：在事务外发出请求，认领过期未记录结果的由任一节点重新投递（至少一次）';
COMMENT ON COLUMN webhook_deliveries.last_attempt_at IS '最近一次尝试的时间';
COMMENT ON COLUMN webhook_deliveries.last_response_status IS '最近一次收到的 HTTP 状态码；没有收到响应时为空';
COMMENT ON COLUMN webhook_deliveries.last_error IS '最近一次失败的原因；成功时为空';
COMMENT ON COLUMN webhook_deliveries.redelivery_of IS '手动重投时为原记录 ID';
COMMENT ON COLUMN webhook_deliveries.created_at IS '创建时间';
COMMENT ON COLUMN webhook_deliveries.completed_at IS '成功或最终失败的时间';

-- ---------------------------------------------------------------------------------------------
-- 事件归档与保留
-- ---------------------------------------------------------------------------------------------
ALTER TABLE sessions ADD COLUMN events_archived_seq bigint CHECK (events_archived_seq >= 0);
ALTER TABLE sessions ADD COLUMN events_archive_from timestamptz;
ALTER TABLE sessions ADD COLUMN events_expired_before timestamptz;

COMMENT ON COLUMN sessions.events_archive_key IS
    '归档事件文件在对象存储中的键（rt/{tenant}/{session}/events/archive-{seq}.jsonl.gz）；为空表示没有归档文件';
COMMENT ON COLUMN sessions.events_archived_at IS '最近一次归档的时间；为空表示从未归档';
COMMENT ON COLUMN sessions.events_archived_seq IS
    '归档覆盖到的最大 seq：不超过它的事件在归档文件中（或已过保留期），之后的在 session_events；为空表示从未归档';
COMMENT ON COLUMN sessions.events_archive_from IS '归档文件中最早的事件时间，保留清理据此判断是否要处理文件；没有文件时为空';
COMMENT ON COLUMN sessions.events_expired_before IS
    '保留清理已执行到的截止时间：早于它的事件都已删除；之后只有在它之后有活动的会话才再被扫描';
COMMENT ON COLUMN sessions.last_activity_at IS '最近一次写入事件的时间，事件归档据此判断 90 天无活动';

-- 归档扫描：原索引只覆盖从未归档的会话，再次归档（归档后又有新事件）也要扫描
DROP INDEX sessions_activity_idx;
CREATE INDEX sessions_archive_idx ON sessions (last_activity_at) WHERE deleted_at IS NULL;
-- 会话清理扫描已删除的会话
CREATE INDEX sessions_deleted_idx ON sessions (deleted_at) WHERE deleted_at IS NOT NULL;
