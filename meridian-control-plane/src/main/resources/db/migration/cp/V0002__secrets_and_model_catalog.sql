-- 凭据库、供应商账号、价格表与模型目录（架构设计「数据架构」中 control-plane 的核心表，T15a，ADR-0045）。
--
-- 都是租户级资源；凭据可以再限定到一个项目。外键是删除被引用资源的最后一道防线：应用先查出引用方返回 409，
-- 外键保证并发删除也不会留下悬空引用。所有时间列由应用按注入的 Clock 写入。

-- ---------------------------------------------------------------------------------------------
-- 凭据：信封加密，主密钥只在密钥管理系统中
-- ---------------------------------------------------------------------------------------------
CREATE TABLE secret_data_keys (
    tenant_id      text        PRIMARY KEY REFERENCES tenants (id),
    key_encryption text        NOT NULL CHECK (key_encryption IN ('local', 'vault')),
    kek_id         text        NOT NULL,
    wrapped_key    text        NOT NULL,
    created_at     timestamptz NOT NULL
);

COMMENT ON TABLE secret_data_keys IS '按租户的数据密钥（AES-256），由主密钥包装后保存；明文只在 control-plane 内存中短暂存在';
COMMENT ON COLUMN secret_data_keys.tenant_id IS '租户 ID：每个租户一把数据密钥';
COMMENT ON COLUMN secret_data_keys.key_encryption IS '包装它的密钥加密服务：local（开发用本地主密钥文件）、vault（HashiCorp Vault Transit）';
COMMENT ON COLUMN secret_data_keys.kek_id IS '主密钥标识：local 为主密钥的 SHA-256 前 16 位，vault 为 Transit 的挂载点与密钥名';
COMMENT ON COLUMN secret_data_keys.wrapped_key IS '包装后的数据密钥：local 为 base64(nonce || 密文 || 标签)，vault 为 Transit 返回的 vault:v… 密文';
COMMENT ON COLUMN secret_data_keys.created_at IS '生成时间';

CREATE TABLE secrets (
    id           text        PRIMARY KEY,
    tenant_id    text        NOT NULL REFERENCES tenants (id),
    project_id   text        REFERENCES projects (id),
    name         text        NOT NULL CHECK (length(name) BETWEEN 1 AND 128),
    version      integer     NOT NULL CHECK (version >= 1),
    ciphertext   bytea       NOT NULL,
    created_by   text        NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    last_used_at timestamptz
);

-- 名称在同一范围（租户级，或同一项目）内唯一；列表按租户游标分页
CREATE UNIQUE INDEX secrets_name_idx ON secrets (tenant_id, COALESCE(project_id, ''), name);
CREATE INDEX secrets_tenant_idx ON secrets (tenant_id, id);

COMMENT ON TABLE secrets IS '凭据：值以租户数据密钥 AES-256-GCM 加密，任何接口都读不出明文（架构设计「凭据」，ADR-0045）';
COMMENT ON COLUMN secrets.id IS '凭据 ID（sec_…）';
COMMENT ON COLUMN secrets.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN secrets.project_id IS '所属项目 ID；为空表示租户级凭据，供租户级资源与全部项目使用';
COMMENT ON COLUMN secrets.name IS '名称';
COMMENT ON COLUMN secrets.version IS '版本：创建为 1，每次轮换加一，参与密文的附加认证数据';
COMMENT ON COLUMN secrets.ciphertext IS 'nonce（12 字节）|| 密文 || 标签（16 字节）；附加认证数据为租户、凭据 ID 与版本';
COMMENT ON COLUMN secrets.created_by IS '创建者的主体标识';
COMMENT ON COLUMN secrets.created_at IS '创建时间';
COMMENT ON COLUMN secrets.updated_at IS '最近一次写入（创建或轮换）的时间';
COMMENT ON COLUMN secrets.last_used_at IS '最近一次被 runtime 解析的时间；为空表示没有用过';

-- ---------------------------------------------------------------------------------------------
-- 供应商账号
-- ---------------------------------------------------------------------------------------------
CREATE TABLE provider_accounts (
    id         text        PRIMARY KEY,
    tenant_id  text        NOT NULL REFERENCES tenants (id),
    name       text        NOT NULL CHECK (length(name) BETWEEN 1 AND 128),
    provider   text        NOT NULL CHECK (provider IN ('openai_compatible', 'anthropic', 'gemini')),
    base_url   text,
    secret_id  text        REFERENCES secrets (id),
    rpm        integer     CHECK (rpm >= 1),
    tpm        integer     CHECK (tpm >= 1),
    created_by text        NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (tenant_id, name)
);

CREATE INDEX provider_accounts_tenant_idx ON provider_accounts (tenant_id, id);
CREATE INDEX provider_accounts_secret_idx ON provider_accounts (secret_id);

COMMENT ON TABLE provider_accounts IS '供应商账号：类型、端点、凭据引用与账号级限流（架构设计「模型调用」）';
COMMENT ON COLUMN provider_accounts.id IS '供应商账号 ID（prv_…）';
COMMENT ON COLUMN provider_accounts.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN provider_accounts.name IS '名称，租户内唯一';
COMMENT ON COLUMN provider_accounts.provider IS '供应商类型：openai_compatible、anthropic、gemini（ADR-0046）';
COMMENT ON COLUMN provider_accounts.base_url IS '端点；为空表示该类型的默认端点（openai_compatible 必填）。写入时按端点地址策略校验';
COMMENT ON COLUMN provider_accounts.secret_id IS '凭据 ID；为空表示不需要凭据（例如内网的推理服务）';
COMMENT ON COLUMN provider_accounts.rpm IS '每分钟请求上限；为空表示不限';
COMMENT ON COLUMN provider_accounts.tpm IS '每分钟 token 上限；为空表示不限';
COMMENT ON COLUMN provider_accounts.created_by IS '创建者的主体标识';
COMMENT ON COLUMN provider_accounts.created_at IS '创建时间';
COMMENT ON COLUMN provider_accounts.updated_at IS '最近一次修改时间';

-- ---------------------------------------------------------------------------------------------
-- 价格表：版本只追加，发布即生效
-- ---------------------------------------------------------------------------------------------
CREATE TABLE price_books (
    id              text        PRIMARY KEY,
    tenant_id       text        NOT NULL REFERENCES tenants (id),
    name            text        NOT NULL CHECK (length(name) BETWEEN 1 AND 128),
    currency        text        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    current_version integer     NOT NULL CHECK (current_version >= 1),
    created_by      text        NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    UNIQUE (tenant_id, name)
);

CREATE INDEX price_books_tenant_idx ON price_books (tenant_id, id);

COMMENT ON TABLE price_books IS '价格表：模型的 token 单价，按版本计价，调价不影响已有用量记录（架构设计「计量与预算」）';
COMMENT ON COLUMN price_books.id IS '价格表 ID（prb_…）';
COMMENT ON COLUMN price_books.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN price_books.name IS '名称，租户内唯一';
COMMENT ON COLUMN price_books.currency IS '币种（ISO 4217，例如 CNY）';
COMMENT ON COLUMN price_books.current_version IS '当前生效的版本：最近发布的版本';
COMMENT ON COLUMN price_books.created_by IS '创建者的主体标识';
COMMENT ON COLUMN price_books.created_at IS '创建时间';
COMMENT ON COLUMN price_books.updated_at IS '最近一次发布版本或改名的时间';

CREATE TABLE price_book_versions (
    price_book_id     text          NOT NULL REFERENCES price_books (id),
    version           integer       NOT NULL CHECK (version >= 1),
    tenant_id         text          NOT NULL REFERENCES tenants (id),
    input_price       numeric(18, 6) NOT NULL CHECK (input_price >= 0),
    output_price      numeric(18, 6) NOT NULL CHECK (output_price >= 0),
    cache_read_price  numeric(18, 6) NOT NULL CHECK (cache_read_price >= 0),
    cache_write_price numeric(18, 6) NOT NULL CHECK (cache_write_price >= 0),
    reasoning_price   numeric(18, 6) NOT NULL CHECK (reasoning_price >= 0),
    effective_from    timestamptz   NOT NULL,
    created_by        text          NOT NULL,
    PRIMARY KEY (price_book_id, version)
);

COMMENT ON TABLE price_book_versions IS '价格表的版本：发布后不可修改，用量记录按其中的版本计价（ADR-0045）';
COMMENT ON COLUMN price_book_versions.price_book_id IS '价格表 ID';
COMMENT ON COLUMN price_book_versions.version IS '版本号，从 1 递增';
COMMENT ON COLUMN price_book_versions.tenant_id IS '所属租户 ID（与价格表相同，租户级仓储按它过滤）';
COMMENT ON COLUMN price_book_versions.input_price IS '输入 token 的单价，每百万 token';
COMMENT ON COLUMN price_book_versions.output_price IS '输出 token 的单价，每百万 token';
COMMENT ON COLUMN price_book_versions.cache_read_price IS '命中提示词缓存的输入 token 的单价，每百万 token';
COMMENT ON COLUMN price_book_versions.cache_write_price IS '写入提示词缓存的输入 token 的单价，每百万 token';
COMMENT ON COLUMN price_book_versions.reasoning_price IS '推理 token 的单价，每百万 token';
COMMENT ON COLUMN price_book_versions.effective_from IS '生效时间：发布时间';
COMMENT ON COLUMN price_book_versions.created_by IS '发布者的主体标识';

-- ---------------------------------------------------------------------------------------------
-- 模型目录
-- ---------------------------------------------------------------------------------------------
CREATE TABLE models (
    id                  text        PRIMARY KEY,
    tenant_id           text        NOT NULL REFERENCES tenants (id),
    provider_account_id text        NOT NULL REFERENCES provider_accounts (id),
    name                text        NOT NULL CHECK (length(name) BETWEEN 1 AND 128),
    kind                text        NOT NULL CHECK (kind IN ('chat', 'embedding', 'rerank')),
    upstream_model      text        NOT NULL CHECK (length(upstream_model) BETWEEN 1 AND 256),
    context_window      integer     NOT NULL CHECK (context_window >= 1),
    max_output_tokens   integer     NOT NULL CHECK (max_output_tokens >= 1),
    supports_tool_calls boolean     NOT NULL,
    supports_vision     boolean     NOT NULL,
    supports_reasoning  boolean     NOT NULL,
    fallback_model_id   text        REFERENCES models (id),
    price_book_id       text        REFERENCES price_books (id),
    base_url            text,
    max_batch_size      integer     CHECK (max_batch_size >= 1),
    rpm                 integer     CHECK (rpm >= 1),
    tpm                 integer     CHECK (tpm >= 1),
    created_by          text        NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    UNIQUE (tenant_id, name),
    CHECK (fallback_model_id IS NULL OR fallback_model_id <> id)
);

CREATE INDEX models_tenant_idx ON models (tenant_id, id);
CREATE INDEX models_provider_account_idx ON models (provider_account_id);
CREATE INDEX models_fallback_idx ON models (fallback_model_id);
CREATE INDEX models_price_book_idx ON models (price_book_id);

COMMENT ON TABLE models IS '模型目录：供应商侧模型、上下文窗口、能力、备用模型与价格表（架构设计「模型调用」，ADR-0045）';
COMMENT ON COLUMN models.id IS '模型 ID（mdl_…），智能体配置与直通接口按它引用';
COMMENT ON COLUMN models.tenant_id IS '所属租户 ID：租户内的全部项目都可以使用';
COMMENT ON COLUMN models.provider_account_id IS '所属供应商账号';
COMMENT ON COLUMN models.name IS '名称，租户内唯一';
COMMENT ON COLUMN models.kind IS '种类：chat、embedding、rerank；后两者只能挂在 openai_compatible 账号下';
COMMENT ON COLUMN models.upstream_model IS '供应商侧的模型名，例如 qwen-plus';
COMMENT ON COLUMN models.context_window IS '上下文窗口，单位 token；压缩阈值据此计算';
COMMENT ON COLUMN models.max_output_tokens IS '请求没有指定时的最大输出 token，预扣按它计算';
COMMENT ON COLUMN models.supports_tool_calls IS '能力：工具调用';
COMMENT ON COLUMN models.supports_vision IS '能力：图片输入';
COMMENT ON COLUMN models.supports_reasoning IS '能力：推理（思考）输出';
COMMENT ON COLUMN models.fallback_model_id IS '备用模型：同租户、同种类；智能体配置没有给出备用模型时使用';
COMMENT ON COLUMN models.price_book_id IS '价格表；为空表示没有定价';
COMMENT ON COLUMN models.base_url IS '本模型的端点，覆盖账号的端点；为空表示用账号的';
COMMENT ON COLUMN models.max_batch_size IS 'Embedding 一次请求的输入条数上限；为空表示默认值';
COMMENT ON COLUMN models.rpm IS '本模型的每分钟请求上限，覆盖账号的；为空表示用账号的';
COMMENT ON COLUMN models.tpm IS '本模型的每分钟 token 上限，覆盖账号的；为空表示用账号的';
COMMENT ON COLUMN models.created_by IS '创建者的主体标识';
COMMENT ON COLUMN models.created_at IS '创建时间';
COMMENT ON COLUMN models.updated_at IS '最近一次修改时间';
