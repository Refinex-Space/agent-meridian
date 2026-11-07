-- 幂等键表：HTTP 请求头 Idempotency-Key 的首次结果，保留 24 小时（架构设计「API 设计」中的「约定」）。
-- 每个对外提供 POST 接口的服务把本片段复制到自己 schema 的迁移中；表建在 Flyway 的默认 schema。
CREATE TABLE IF NOT EXISTS idempotency_keys (
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
CREATE INDEX IF NOT EXISTS idempotency_keys_expires_idx ON idempotency_keys (expires_at);

COMMENT ON TABLE idempotency_keys IS 'Idempotency-Key 的首次请求与结果，24 小时内同键同请求体返回首次结果';
COMMENT ON COLUMN idempotency_keys.principal IS '调用方身份，例如 API Key ID；同一租户内不同调用方的同名键互不影响';
COMMENT ON COLUMN idempotency_keys.idempotency_key IS '调用方提供的 Idempotency-Key 请求头';
COMMENT ON COLUMN idempotency_keys.request_hash IS '请求指纹（方法、路径、请求体的 SHA-256），同键不同指纹视为冲突';
COMMENT ON COLUMN idempotency_keys.status IS 'in_progress：首次请求处理中；completed：已保存结果';
COMMENT ON COLUMN idempotency_keys.response_status IS '首次请求的 HTTP 状态码，completed 时有值';
COMMENT ON COLUMN idempotency_keys.response_body IS '首次请求的响应体，completed 时有值，可为空';
COMMENT ON COLUMN idempotency_keys.created_at IS '首次请求开始处理的时间';
COMMENT ON COLUMN idempotency_keys.expires_at IS '过期时间，过期后同一个键可以重新使用';
