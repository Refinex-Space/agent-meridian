-- 智能体与版本、环境与修订、技能与版本、MCP 服务、租户与项目策略（架构设计「数据架构」中 control-plane 的核心表，T15b，ADR-0047）。
--
-- 都是项目级资源（租户策略的 project_id 为空）。智能体版本发布后只读：规格快照写进 spec，引用的资源另记在 agent_version_refs，
-- 删除被引用的资源时据此返回 409。所有时间列由应用按注入的 Clock 写入。

-- ---------------------------------------------------------------------------------------------
-- 智能体与版本
-- ---------------------------------------------------------------------------------------------
CREATE TABLE agents (
    id          text        PRIMARY KEY,
    tenant_id   text        NOT NULL REFERENCES tenants (id),
    project_id  text        NOT NULL REFERENCES projects (id),
    name        text        NOT NULL CHECK (name ~ '^[a-z][a-z0-9_-]{0,63}$'),
    description text        NOT NULL DEFAULT '' CHECK (length(description) <= 2000),
    created_by  text        NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL,
    deleted_at  timestamptz
);

-- 名字在项目内唯一（已删除的不占用）；列表按项目游标分页
CREATE UNIQUE INDEX agents_name_idx ON agents (project_id, name) WHERE deleted_at IS NULL;
CREATE INDEX agents_project_idx ON agents (project_id, id) WHERE deleted_at IS NULL;

COMMENT ON TABLE agents IS '智能体：版本的容器，属于项目；删除为软删除，运行中的会话继续使用已绑定的版本';
COMMENT ON COLUMN agents.id IS '智能体 ID（agt_…）';
COMMENT ON COLUMN agents.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN agents.project_id IS '所属项目 ID';
COMMENT ON COLUMN agents.name IS '名字，项目内唯一，发布时写进规格（AgentScope 智能体名）';
COMMENT ON COLUMN agents.description IS '说明';
COMMENT ON COLUMN agents.created_by IS '创建者的主体标识';
COMMENT ON COLUMN agents.created_at IS '创建时间';
COMMENT ON COLUMN agents.updated_at IS '最近一次修改时间';
COMMENT ON COLUMN agents.deleted_at IS '删除时间；不为空表示已删除，不能再用它创建会话';

CREATE TABLE agent_versions (
    id           text        PRIMARY KEY,
    tenant_id    text        NOT NULL REFERENCES tenants (id),
    project_id   text        NOT NULL REFERENCES projects (id),
    agent_id     text        NOT NULL REFERENCES agents (id),
    version      integer     NOT NULL CHECK (version >= 1),
    status       text        NOT NULL CHECK (status IN ('draft', 'published')),
    spec         jsonb       NOT NULL,
    created_by   text        NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    published_by text,
    published_at timestamptz,
    UNIQUE (agent_id, version),
    CHECK ((status = 'published') = (published_at IS NOT NULL AND published_by IS NOT NULL))
);

-- 当前版本：版本号最大的已发布版本
CREATE INDEX agent_versions_published_idx ON agent_versions (agent_id, version DESC) WHERE status = 'published';

COMMENT ON TABLE agent_versions IS '智能体版本：草稿可修改，发布后只读（架构设计「核心概念」）';
COMMENT ON COLUMN agent_versions.id IS '智能体版本 ID（agv_…），会话绑定它';
COMMENT ON COLUMN agent_versions.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN agent_versions.project_id IS '所属项目 ID';
COMMENT ON COLUMN agent_versions.agent_id IS '所属智能体 ID';
COMMENT ON COLUMN agent_versions.version IS '版本号，按智能体从 1 递增';
COMMENT ON COLUMN agent_versions.status IS 'draft（草稿）或 published（已发布，不可变）';
COMMENT ON COLUMN agent_versions.spec IS '规格（schema/agent-spec.schema.json）；已发布的版本带智能体名、MCP 服务与技能名的快照';
COMMENT ON COLUMN agent_versions.created_by IS '创建者的主体标识';
COMMENT ON COLUMN agent_versions.created_at IS '创建时间';
COMMENT ON COLUMN agent_versions.updated_at IS '最近一次修改时间';
COMMENT ON COLUMN agent_versions.published_by IS '发布者的主体标识；草稿为空';
COMMENT ON COLUMN agent_versions.published_at IS '发布时间；草稿为空';

CREATE TABLE agent_version_refs (
    agent_version_id text NOT NULL REFERENCES agent_versions (id),
    tenant_id        text NOT NULL REFERENCES tenants (id),
    ref_type         text NOT NULL CHECK (ref_type IN ('model', 'skill', 'mcp_server', 'secret', 'knowledge_base')),
    ref_id           text NOT NULL,
    PRIMARY KEY (agent_version_id, ref_type, ref_id)
);

CREATE INDEX agent_version_refs_ref_idx ON agent_version_refs (ref_type, ref_id);

COMMENT ON TABLE agent_version_refs IS '已发布版本引用的资源（含子智能体）：删除模型、技能、MCP 服务、凭据时据此列出引用方';
COMMENT ON COLUMN agent_version_refs.agent_version_id IS '已发布的智能体版本 ID';
COMMENT ON COLUMN agent_version_refs.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN agent_version_refs.ref_type IS '被引用资源的类型：model、skill、mcp_server、secret、knowledge_base';
COMMENT ON COLUMN agent_version_refs.ref_id IS '被引用资源的 ID';

-- ---------------------------------------------------------------------------------------------
-- 环境与修订
-- ---------------------------------------------------------------------------------------------
CREATE TABLE environments (
    id         text        PRIMARY KEY,
    tenant_id  text        NOT NULL REFERENCES tenants (id),
    project_id text        NOT NULL REFERENCES projects (id),
    name       text        NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    revision   bigint      NOT NULL CHECK (revision >= 1),
    created_by text        NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz
);

CREATE UNIQUE INDEX environments_name_idx ON environments (project_id, name) WHERE deleted_at IS NULL;
CREATE INDEX environments_project_idx ON environments (project_id, id) WHERE deleted_at IS NULL;

COMMENT ON TABLE environments IS '环境：执行档位、沙箱规格与出网白名单，修改即递增修订号（架构设计「核心概念」）；删除为软删除';
COMMENT ON COLUMN environments.id IS '环境 ID（env_…）';
COMMENT ON COLUMN environments.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN environments.project_id IS '所属项目 ID';
COMMENT ON COLUMN environments.name IS '名字，项目内唯一';
COMMENT ON COLUMN environments.revision IS '当前修订号，等于 environment_revisions 中最大的修订号';
COMMENT ON COLUMN environments.created_by IS '创建者的主体标识';
COMMENT ON COLUMN environments.created_at IS '创建时间';
COMMENT ON COLUMN environments.updated_at IS '最近一次修改时间';
COMMENT ON COLUMN environments.deleted_at IS '删除时间；不为空表示已删除，不能再用它创建会话';

CREATE TABLE environment_revisions (
    environment_id   text        NOT NULL REFERENCES environments (id),
    revision         bigint      NOT NULL CHECK (revision >= 1),
    tenant_id        text        NOT NULL REFERENCES tenants (id),
    project_id       text        NOT NULL REFERENCES projects (id),
    tier             text        NOT NULL CHECK (tier IN ('none', 'sandbox')),
    size             text        CHECK (size IN ('small', 'medium', 'large')),
    egress_allowlist text[]      NOT NULL,
    created_by       text        NOT NULL,
    created_at       timestamptz NOT NULL,
    PRIMARY KEY (environment_id, revision),
    CHECK ((tier = 'sandbox') = (size IS NOT NULL))
);

COMMENT ON TABLE environment_revisions IS '环境的修订历史，只追加；会话固化修订号，运行中的会话按它读取';
COMMENT ON COLUMN environment_revisions.environment_id IS '环境 ID';
COMMENT ON COLUMN environment_revisions.revision IS '修订号，从 1 开始';
COMMENT ON COLUMN environment_revisions.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN environment_revisions.project_id IS '所属项目 ID';
COMMENT ON COLUMN environment_revisions.tier IS '执行档位：none 或 sandbox';
COMMENT ON COLUMN environment_revisions.size IS '沙箱规格：small、medium、large；none 档为空';
COMMENT ON COLUMN environment_revisions.egress_allowlist IS '出网白名单（域名，*. 前缀匹配子域名）；空数组表示无出网';
COMMENT ON COLUMN environment_revisions.created_by IS '产生该修订的主体标识';
COMMENT ON COLUMN environment_revisions.created_at IS '产生时间';

-- ---------------------------------------------------------------------------------------------
-- 技能与版本
-- ---------------------------------------------------------------------------------------------
CREATE TABLE skills (
    id             text        PRIMARY KEY,
    tenant_id      text        NOT NULL REFERENCES tenants (id),
    project_id     text        NOT NULL REFERENCES projects (id),
    name           text        NOT NULL CHECK (name ~ '^[a-z0-9][a-z0-9-]{0,63}$'),
    description    text        NOT NULL,
    latest_version integer     NOT NULL CHECK (latest_version >= 1),
    created_by     text        NOT NULL,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    deleted_at     timestamptz
);

CREATE UNIQUE INDEX skills_name_idx ON skills (project_id, name) WHERE deleted_at IS NULL;
CREATE INDEX skills_project_idx ON skills (project_id, id) WHERE deleted_at IS NULL;

COMMENT ON TABLE skills IS '技能：版本化的 SKILL.md 与附带文件，属于项目；删除为软删除，已发布版本绑定的内容仍可读取';
COMMENT ON COLUMN skills.id IS '技能 ID（skl_…）';
COMMENT ON COLUMN skills.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN skills.project_id IS '所属项目 ID';
COMMENT ON COLUMN skills.name IS '技能名（SKILL.md frontmatter 的 name），项目内唯一';
COMMENT ON COLUMN skills.description IS '最新版本 frontmatter 的 description';
COMMENT ON COLUMN skills.latest_version IS '最新版本号';
COMMENT ON COLUMN skills.created_by IS '创建者的主体标识';
COMMENT ON COLUMN skills.created_at IS '创建时间';
COMMENT ON COLUMN skills.updated_at IS '最近一次上传新版本的时间';
COMMENT ON COLUMN skills.deleted_at IS '删除时间；不为空表示已删除';

CREATE TABLE skill_versions (
    skill_id    text        NOT NULL REFERENCES skills (id),
    version     integer     NOT NULL CHECK (version >= 1),
    tenant_id   text        NOT NULL REFERENCES tenants (id),
    project_id  text        NOT NULL REFERENCES projects (id),
    name        text        NOT NULL,
    description text        NOT NULL,
    files       jsonb       NOT NULL,
    object_root text        NOT NULL,
    created_by  text        NOT NULL,
    created_at  timestamptz NOT NULL,
    PRIMARY KEY (skill_id, version)
);

COMMENT ON TABLE skill_versions IS '技能版本，只追加、不可修改；文件内容在对象存储';
COMMENT ON COLUMN skill_versions.skill_id IS '技能 ID';
COMMENT ON COLUMN skill_versions.version IS '版本号，从 1 开始';
COMMENT ON COLUMN skill_versions.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN skill_versions.project_id IS '所属项目 ID';
COMMENT ON COLUMN skill_versions.name IS 'frontmatter 的 name';
COMMENT ON COLUMN skill_versions.description IS 'frontmatter 的 description';
COMMENT ON COLUMN skill_versions.files IS '文件清单 [{path, size, sha256}]，按路径排序';
COMMENT ON COLUMN skill_versions.object_root IS '对象存储中该版本的键前缀，文件键为前缀加相对路径';
COMMENT ON COLUMN skill_versions.created_by IS '上传者的主体标识';
COMMENT ON COLUMN skill_versions.created_at IS '上传时间';

-- ---------------------------------------------------------------------------------------------
-- MCP 服务
-- ---------------------------------------------------------------------------------------------
CREATE TABLE mcp_servers (
    id                text        PRIMARY KEY,
    tenant_id         text        NOT NULL REFERENCES tenants (id),
    project_id        text        NOT NULL REFERENCES projects (id),
    name              text        NOT NULL CHECK (name ~ '^[a-z][a-z0-9_]{0,31}$'),
    transport         text        NOT NULL CHECK (transport IN ('streamable_http', 'sse')),
    url               text        NOT NULL,
    header_secret_ids jsonb       NOT NULL,
    side_effect       text        CHECK (side_effect IN ('none', 'idempotent', 'non_idempotent')),
    tool_side_effects jsonb       NOT NULL,
    tools             jsonb       NOT NULL,
    probed_at         timestamptz NOT NULL,
    created_by        text        NOT NULL,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    UNIQUE (project_id, name)
);

CREATE INDEX mcp_servers_project_idx ON mcp_servers (project_id, id);

COMMENT ON TABLE mcp_servers IS 'MCP 服务：端点、传输、凭据引用与工具的副作用等级，登记时探测并保存工具清单（架构设计「平台工具」）';
COMMENT ON COLUMN mcp_servers.id IS 'MCP 服务 ID（mcp_…）';
COMMENT ON COLUMN mcp_servers.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN mcp_servers.project_id IS '所属项目 ID';
COMMENT ON COLUMN mcp_servers.name IS '服务名，项目内唯一，工具名前缀 mcp__{name}__ 中的部分';
COMMENT ON COLUMN mcp_servers.transport IS '传输：streamable_http 或 sse';
COMMENT ON COLUMN mcp_servers.url IS '服务端点，写入时按端点地址策略校验';
COMMENT ON COLUMN mcp_servers.header_secret_ids IS '注入的请求头：头名 → 凭据 ID；凭据值是请求头的完整取值';
COMMENT ON COLUMN mcp_servers.side_effect IS '服务级副作用等级；为空时按 non_idempotent 处理';
COMMENT ON COLUMN mcp_servers.tool_side_effects IS '按工具单独标注的副作用等级：工具名 → 等级';
COMMENT ON COLUMN mcp_servers.tools IS '最近一次探测到的工具清单 [{name, description}]，按名字排序';
COMMENT ON COLUMN mcp_servers.probed_at IS '最近一次探测成功的时间';
COMMENT ON COLUMN mcp_servers.created_by IS '登记者的主体标识';
COMMENT ON COLUMN mcp_servers.created_at IS '登记时间';
COMMENT ON COLUMN mcp_servers.updated_at IS '最近一次修改或探测的时间';

-- ---------------------------------------------------------------------------------------------
-- 租户与项目策略
-- ---------------------------------------------------------------------------------------------
CREATE TABLE permission_rules (
    id               text        PRIMARY KEY,
    tenant_id        text        NOT NULL REFERENCES tenants (id),
    project_id       text        REFERENCES projects (id),
    tool_name        text        NOT NULL CHECK (length(tool_name) BETWEEN 1 AND 200),
    rule_content     text        CHECK (length(rule_content) <= 1000),
    behavior         text        NOT NULL CHECK (behavior IN ('allow', 'deny', 'ask')),
    source           text        NOT NULL CHECK (source IN ('policy', 'remembered')),
    source_action_id text,
    created_by       text        NOT NULL,
    created_at       timestamptz NOT NULL,
    CHECK ((source = 'remembered') = (source_action_id IS NOT NULL)),
    CHECK (source = 'policy' OR project_id IS NOT NULL)
);

-- 同一层中相同的规则（工具、内容、结果）只有一条；内容为空（整工具规则）与空字符串区分开
CREATE UNIQUE INDEX permission_rules_unique_idx ON permission_rules (
    tenant_id, COALESCE(project_id, ''), tool_name, COALESCE(rule_content, ''), (rule_content IS NULL), behavior);
CREATE INDEX permission_rules_scope_idx ON permission_rules (tenant_id, project_id, id);

COMMENT ON TABLE permission_rules IS '租户策略（project_id 为空）与项目策略：五层权限合成的第 2、3 层（架构设计「权限判定」）';
COMMENT ON COLUMN permission_rules.id IS '规则 ID（prl_…）';
COMMENT ON COLUMN permission_rules.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN permission_rules.project_id IS '所属项目 ID；为空表示租户策略';
COMMENT ON COLUMN permission_rules.tool_name IS '工具名';
COMMENT ON COLUMN permission_rules.rule_content IS '规则内容；为空表示整工具规则';
COMMENT ON COLUMN permission_rules.behavior IS '命中后的结果：allow、deny、ask';
COMMENT ON COLUMN permission_rules.source IS 'policy：管理员添加；remembered：审批时“记住此决定”（项目级）写入';
COMMENT ON COLUMN permission_rules.source_action_id IS '记住的决定来源的待处理动作 ID；管理员添加的为空';
COMMENT ON COLUMN permission_rules.created_by IS '添加者或做出决定的主体标识';
COMMENT ON COLUMN permission_rules.created_at IS '添加时间';
