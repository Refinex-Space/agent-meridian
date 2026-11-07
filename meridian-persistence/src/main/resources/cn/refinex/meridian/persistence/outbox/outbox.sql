-- outbox 表：业务事务内写入、由 OutboxRelay 投递到 Redis Stream 的消息（架构设计「跨服务数据流」）。
-- 使用 outbox 的服务把本片段复制到自己 schema 的第一个迁移中；表建在 Flyway 的默认 schema（cp、rt 或 kb）。
CREATE TABLE IF NOT EXISTS outbox (
    id           text        PRIMARY KEY,
    topic        text        NOT NULL,
    payload      jsonb       NOT NULL,
    created_at   timestamptz NOT NULL,
    published_at timestamptz
);

-- Relay 只扫描未发布的消息，按 id（ULID，大致按时间）顺序取
CREATE INDEX IF NOT EXISTS outbox_unpublished_idx ON outbox (id) WHERE published_at IS NULL;

COMMENT ON TABLE outbox IS '待可靠投递的消息：业务事务内写入，OutboxRelay 发布到 Redis Stream 后标记';
COMMENT ON COLUMN outbox.id IS '消息 ID（ULID），消费方据此幂等';
COMMENT ON COLUMN outbox.topic IS '主题，即目标 Redis Stream 的键，例如 usage、audit';
COMMENT ON COLUMN outbox.payload IS '消息载荷（JSON，字段 snake_case），自带租户与项目';
COMMENT ON COLUMN outbox.created_at IS '写入时间';
COMMENT ON COLUMN outbox.published_at IS '发布时间；为空表示尚未发布';
