-- control-plane 的租户体系表（架构设计「数据架构」中「核心表」的 control-plane 部分，T14a，ADR-0042）：租户、租户设置、项目、
-- 成员、角色绑定、API Key，以及 outbox 与幂等键表。模型目录、凭据、智能体、预算、用量与审计的表由后续任务各自迁移。
--
-- 对象建在 Flyway 的默认 schema cp 中（迁移约定见 meridian-persistence 的 MigrationConventions），由迁移角色 meridian_migrator
-- 执行；应用以只有 DML 的 meridian_app 运行。所有时间列由应用按注入的 Clock 写入，不使用数据库的 now()。

-- ---------------------------------------------------------------------------------------------
-- 租户
-- ---------------------------------------------------------------------------------------------
CREATE TABLE tenants (
    id                   text        PRIMARY KEY,
    name                 text        NOT NULL CHECK (length(name) BETWEEN 1 AND 128),
    max_concurrent_turns integer     NOT NULL CHECK (max_concurrent_turns >= 1),
    event_retention_days integer     NOT NULL CHECK (event_retention_days >= 1),
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    deleted_at           timestamptz
);

COMMENT ON TABLE tenants IS '租户：计费与管理的单位';
COMMENT ON COLUMN tenants.id IS '租户 ID（ten_…）';
COMMENT ON COLUMN tenants.name IS '名称';
COMMENT ON COLUMN tenants.max_concurrent_turns IS '并发配额：同时执行的轮次上限，经内部接口下发到 runtime 的 rt.tenant_concurrency.max_running';
COMMENT ON COLUMN tenants.event_retention_days IS '会话事件的保留天数（架构设计「数据保留」）';
COMMENT ON COLUMN tenants.created_at IS '创建时间';
COMMENT ON COLUMN tenants.updated_at IS '最近一次修改时间';
COMMENT ON COLUMN tenants.deleted_at IS '删除时间；不为空表示已删除（软删除），其下的 API Key 已吊销';

CREATE TABLE tenant_settings (
    tenant_id     text        PRIMARY KEY REFERENCES tenants (id),
    system_prompt text        NOT NULL DEFAULT '',
    web_search    jsonb,
    updated_at    timestamptz NOT NULL
);

COMMENT ON TABLE tenant_settings IS '租户设置：系统提示词的租户层与联网搜索后端（ADR-0035）；没有行表示全部为默认值';
COMMENT ON COLUMN tenant_settings.tenant_id IS '租户 ID';
COMMENT ON COLUMN tenant_settings.system_prompt IS '租户层系统提示词；空字符串表示没有';
COMMENT ON COLUMN tenant_settings.web_search IS '联网搜索后端 {backend, base_url, secret_id}；为空表示未配置';
COMMENT ON COLUMN tenant_settings.updated_at IS '最近一次修改时间';

-- ---------------------------------------------------------------------------------------------
-- 项目
-- ---------------------------------------------------------------------------------------------
CREATE TABLE projects (
    id          text        PRIMARY KEY,
    tenant_id   text        NOT NULL REFERENCES tenants (id),
    name        text        NOT NULL CHECK (length(name) BETWEEN 1 AND 128),
    description text        NOT NULL DEFAULT '',
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    deleted_at  timestamptz
);

-- 项目列表按租户游标分页（ID 为 ULID，按时间有序）
CREATE INDEX projects_tenant_idx ON projects (tenant_id, id) WHERE deleted_at IS NULL;

COMMENT ON TABLE projects IS '项目：隔离与授权的基本单位';
COMMENT ON COLUMN projects.id IS '项目 ID（prj_…）';
COMMENT ON COLUMN projects.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN projects.name IS '名称';
COMMENT ON COLUMN projects.description IS '说明；空字符串表示没有';
COMMENT ON COLUMN projects.created_at IS '创建时间';
COMMENT ON COLUMN projects.updated_at IS '最近一次修改时间';
COMMENT ON COLUMN projects.deleted_at IS '删除时间；不为空表示已删除（软删除），其下的 API Key 已吊销';

-- ---------------------------------------------------------------------------------------------
-- 成员与角色绑定
-- ---------------------------------------------------------------------------------------------
CREATE TABLE members (
    id           text        PRIMARY KEY,
    tenant_id    text        NOT NULL REFERENCES tenants (id),
    subject      text        NOT NULL CHECK (length(subject) BETWEEN 1 AND 255),
    email        text        NOT NULL DEFAULT '',
    display_name text        NOT NULL DEFAULT '',
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    UNIQUE (tenant_id, subject)
);

-- 列出某个用户所在的租户（认证查找，跨租户）
CREATE INDEX members_subject_idx ON members (subject);

COMMENT ON TABLE members IS '租户成员：按身份提供方的主体标识识别（ADR-0042）';
COMMENT ON COLUMN members.id IS '成员 ID（mbr_…）';
COMMENT ON COLUMN members.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN members.subject IS '主体标识（OIDC 的 sub），租户内唯一';
COMMENT ON COLUMN members.email IS '邮箱，只用于展示；空字符串表示没有';
COMMENT ON COLUMN members.display_name IS '显示名称；空字符串表示没有';
COMMENT ON COLUMN members.created_at IS '加入时间';
COMMENT ON COLUMN members.updated_at IS '最近一次修改时间';

CREATE TABLE role_bindings (
    id         text        PRIMARY KEY,
    tenant_id  text        NOT NULL REFERENCES tenants (id),
    member_id  text        NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    role       text        NOT NULL
                           CHECK (role IN ('tenant_owner', 'tenant_admin', 'project_developer', 'project_viewer', 'approver')),
    project_id text        REFERENCES projects (id),
    created_at timestamptz NOT NULL,
    -- 租户级角色不带项目，项目级角色必须带项目
    CHECK ((role IN ('tenant_owner', 'tenant_admin')) = (project_id IS NULL))
);

-- 同一成员在同一范围只有一个同名角色
CREATE UNIQUE INDEX role_bindings_unique_idx ON role_bindings (member_id, role, COALESCE(project_id, ''));
-- 列表按租户游标分页；授权时按成员查找
CREATE INDEX role_bindings_tenant_idx ON role_bindings (tenant_id, id);
CREATE INDEX role_bindings_member_idx ON role_bindings (member_id);

COMMENT ON TABLE role_bindings IS '角色绑定：成员在租户或某项目中的一个角色（架构设计「授权」）';
COMMENT ON COLUMN role_bindings.id IS '角色绑定 ID（rbd_…）';
COMMENT ON COLUMN role_bindings.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN role_bindings.member_id IS '成员 ID；成员删除时随之删除';
COMMENT ON COLUMN role_bindings.role IS '角色：tenant_owner、tenant_admin（租户级），project_developer、project_viewer、approver（项目级）';
COMMENT ON COLUMN role_bindings.project_id IS '项目 ID；租户级角色为空';
COMMENT ON COLUMN role_bindings.created_at IS '授予时间';

-- ---------------------------------------------------------------------------------------------
-- API Key
-- ---------------------------------------------------------------------------------------------
CREATE TABLE api_keys (
    id                    text        PRIMARY KEY,
    tenant_id             text        NOT NULL REFERENCES tenants (id),
    project_id            text        NOT NULL REFERENCES projects (id),
    name                  text        NOT NULL CHECK (length(name) BETWEEN 1 AND 128),
    key_hash              text        NOT NULL UNIQUE CHECK (key_hash ~ '^[0-9a-f]{64}$'),
    key_prefix            text        NOT NULL,
    scopes                text[]      NOT NULL,
    rate_limit_per_minute integer     NOT NULL CHECK (rate_limit_per_minute >= 1),
    expires_at            timestamptz,
    created_by            text        NOT NULL,
    created_at            timestamptz NOT NULL,
    revoked_at            timestamptz
);

-- 列表按项目游标分页
CREATE INDEX api_keys_project_idx ON api_keys (tenant_id, project_id, id);

COMMENT ON TABLE api_keys IS 'API Key：只存 SHA-256 与随机部分的前 8 位，明文只在创建响应中出现一次（架构设计「认证」，ADR-0042）';
COMMENT ON COLUMN api_keys.id IS 'API Key ID（key_…）';
COMMENT ON COLUMN api_keys.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN api_keys.project_id IS '所属项目 ID';
COMMENT ON COLUMN api_keys.name IS '名称';
COMMENT ON COLUMN api_keys.key_hash IS '明文的 SHA-256（64 位小写十六进制），按它校验';
COMMENT ON COLUMN api_keys.key_prefix IS '明文中 mk_live_ 之后的前 8 位，用于辨认';
COMMENT ON COLUMN api_keys.scopes IS '作用域取值，例如 sessions:read';
COMMENT ON COLUMN api_keys.rate_limit_per_minute IS '每分钟请求上限：令牌桶的容量与每分钟补充量';
COMMENT ON COLUMN api_keys.expires_at IS '过期时间；为空表示不过期';
COMMENT ON COLUMN api_keys.created_by IS '创建者的主体标识';
COMMENT ON COLUMN api_keys.created_at IS '创建时间';
COMMENT ON COLUMN api_keys.revoked_at IS '吊销时间；为空表示未吊销';

-- ---------------------------------------------------------------------------------------------
-- outbox 与幂等键（片段复制自 meridian-persistence）
-- ---------------------------------------------------------------------------------------------
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
