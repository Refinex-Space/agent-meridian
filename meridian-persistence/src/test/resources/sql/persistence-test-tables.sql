-- persistence 集成测试用的表，建在 rt schema。
CREATE TABLE IF NOT EXISTS t02_notes (
    tenant_id  text        NOT NULL,
    project_id text        NOT NULL,
    id         text        PRIMARY KEY,
    body       text        NOT NULL,
    tags       text[]      NOT NULL DEFAULT '{}',
    attributes jsonb,
    created_at timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS t02_tenant_settings (
    tenant_id text NOT NULL,
    name      text NOT NULL,
    value     text NOT NULL,
    PRIMARY KEY (tenant_id, name)
);

CREATE TABLE IF NOT EXISTS t02_consumed_messages (
    message_id text PRIMARY KEY,
    payload    text NOT NULL
);
