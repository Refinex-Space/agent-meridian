-- Webhook（T17a，架构设计「Webhook」，ADR-0049）：项目级的推送配置。runtime 经内部接口读取并投递，投递记录在 rt。

CREATE TABLE webhooks (
    id                text        PRIMARY KEY,
    tenant_id         text        NOT NULL REFERENCES tenants (id),
    project_id        text        NOT NULL REFERENCES projects (id),
    url               text        NOT NULL,
    events            text[]      NOT NULL
                                  CHECK (cardinality(events) >= 1
                                         AND events <@ ARRAY['session.status_idle', 'session.status_requires_action',
                                                             'session.error', 'session.status_terminated']::text[]),
    description       text,
    enabled           boolean     NOT NULL,
    secret_ciphertext bytea       NOT NULL,
    created_by        text        NOT NULL,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL
);

CREATE INDEX webhooks_project_idx ON webhooks (tenant_id, project_id, id);

COMMENT ON TABLE webhooks IS 'Webhook：会话状态变化时 runtime 向业务系统推送事件（架构设计「Webhook」，ADR-0049）';
COMMENT ON COLUMN webhooks.id IS 'Webhook ID（whk_…）';
COMMENT ON COLUMN webhooks.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN webhooks.project_id IS '所属项目 ID';
COMMENT ON COLUMN webhooks.url IS '接收方地址（http 或 https），写入时按 Webhook 的地址策略校验，runtime 连接时再校验解析结果';
COMMENT ON COLUMN webhooks.events IS '订阅的事件类型：session.status_idle、session.status_requires_action、session.error、session.status_terminated 的非空子集';
COMMENT ON COLUMN webhooks.description IS '说明；可为空';
COMMENT ON COLUMN webhooks.enabled IS '是否启用；停用时不扇出新的投递，已有的投递以失败结束';
COMMENT ON COLUMN webhooks.secret_ciphertext IS '签名密钥：nonce（12 字节）|| 密文 || 标签（16 字节），用租户数据密钥加密，附加认证数据为租户与 Webhook ID';
COMMENT ON COLUMN webhooks.created_by IS '创建者的主体标识';
COMMENT ON COLUMN webhooks.created_at IS '创建时间';
COMMENT ON COLUMN webhooks.updated_at IS '最近一次修改时间';
