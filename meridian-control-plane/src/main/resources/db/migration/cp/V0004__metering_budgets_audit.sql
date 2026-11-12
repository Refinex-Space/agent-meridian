-- 用量账本、审计日志与四级预算（架构设计「数据架构」「计量与预算」，T16，ADR-0048）。
--
-- usage_records 与 audit_logs 按月分区（UTC 月份），主键 (id, occurred_at)：id 是 outbox 消息 ID，occurred_at 取自消息载荷，重投时
-- 落在同一分区，INSERT ... ON CONFLICT DO NOTHING 即幂等。两张表只追加：应用角色只有 INSERT 与 SELECT。分区由迁移角色拥有的
-- SECURITY DEFINER 函数创建与删除，应用角色不能建表（deploy/docker/postgres/initdb/10-meridian.sh）。

-- ---------------------------------------------------------------------------------------------
-- 用量账本
-- ---------------------------------------------------------------------------------------------
CREATE TABLE usage_records (
    id                  text           NOT NULL,
    occurred_at         timestamptz    NOT NULL,
    kind                text           NOT NULL CHECK (kind IN ('model_tokens', 'embedding_tokens', 'rerank_tokens',
                                                                'sandbox_vcpu_seconds', 'search_calls')),
    tenant_id           text           NOT NULL,
    project_id          text,
    api_key_id          text,
    agent_id            text,
    agent_version_id    text,
    session_id          text,
    thread_id           text,
    turn_id             text,
    request_id          text,
    model_id            text,
    provider_account_id text,
    upstream_model      text,
    purpose             text,
    fallback            boolean        NOT NULL DEFAULT false,
    usage_source        text,
    input_tokens        bigint         NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens       bigint         NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    cache_read_tokens   bigint         NOT NULL DEFAULT 0 CHECK (cache_read_tokens >= 0),
    cache_write_tokens  bigint         NOT NULL DEFAULT 0 CHECK (cache_write_tokens >= 0),
    reasoning_tokens    bigint         NOT NULL DEFAULT 0 CHECK (reasoning_tokens >= 0),
    quantity            numeric(20, 6) NOT NULL CHECK (quantity >= 0),
    price_book_id       text,
    price_book_version  integer,
    currency            text,
    cost                numeric(24, 12) CHECK (cost >= 0),
    attributes          jsonb          NOT NULL DEFAULT '{}'::jsonb,
    recorded_at         timestamptz    NOT NULL,
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE INDEX usage_records_tenant_time_idx ON usage_records (tenant_id, occurred_at);
CREATE INDEX usage_records_project_time_idx ON usage_records (tenant_id, project_id, occurred_at);

COMMENT ON TABLE usage_records IS '用量账本：计费的依据，只追加，按月分区，保留 3 年（架构设计「计量与预算」「数据保留」）';
COMMENT ON COLUMN usage_records.id IS 'outbox 消息 ID：消费者按 (id, occurred_at) 幂等';
COMMENT ON COLUMN usage_records.occurred_at IS '用量发生（结算）的时间，取自消息载荷；分区键';
COMMENT ON COLUMN usage_records.kind IS '计量种类：model_tokens、embedding_tokens、rerank_tokens、sandbox_vcpu_seconds、search_calls';
COMMENT ON COLUMN usage_records.tenant_id IS '租户 ID（不加外键：账本不随租户删除）';
COMMENT ON COLUMN usage_records.project_id IS '项目 ID';
COMMENT ON COLUMN usage_records.api_key_id IS '发起调用的 API Key；控制台用户与平台内部发起时为空';
COMMENT ON COLUMN usage_records.agent_id IS '智能体 ID：消费时按智能体版本查得，用于按智能体聚合';
COMMENT ON COLUMN usage_records.agent_version_id IS '智能体版本 ID';
COMMENT ON COLUMN usage_records.session_id IS '会话 ID';
COMMENT ON COLUMN usage_records.thread_id IS '线程 ID';
COMMENT ON COLUMN usage_records.turn_id IS '轮次 ID';
COMMENT ON COLUMN usage_records.request_id IS '模型调用 ID，与事件、日志关联';
COMMENT ON COLUMN usage_records.model_id IS '实际调用的模型 ID';
COMMENT ON COLUMN usage_records.provider_account_id IS '供应商账号 ID';
COMMENT ON COLUMN usage_records.upstream_model IS '供应商侧的模型名';
COMMENT ON COLUMN usage_records.purpose IS '调用用途：reasoning、compaction、embedding、rerank';
COMMENT ON COLUMN usage_records.fallback IS '是否为降级到备用模型的调用';
COMMENT ON COLUMN usage_records.usage_source IS '用量来源：provider（供应商报告）或 estimated（估算）';
COMMENT ON COLUMN usage_records.input_tokens IS '输入 token';
COMMENT ON COLUMN usage_records.output_tokens IS '输出 token';
COMMENT ON COLUMN usage_records.cache_read_tokens IS '命中提示词缓存的输入 token（输入的子集）';
COMMENT ON COLUMN usage_records.cache_write_tokens IS '写入提示词缓存的输入 token（输入的子集）';
COMMENT ON COLUMN usage_records.reasoning_tokens IS '推理用的输出 token（输出的子集）';
COMMENT ON COLUMN usage_records.quantity IS '计量数量：token 种类为输入加输出，沙箱为 vCPU 秒，搜索为次数';
COMMENT ON COLUMN usage_records.price_book_id IS '计价用的价格表；没有定价时为空';
COMMENT ON COLUMN usage_records.price_book_version IS '计价用的价格表版本：消息中结算时的版本，调价不影响已有记录';
COMMENT ON COLUMN usage_records.currency IS '成本的币种（价格表的币种）';
COMMENT ON COLUMN usage_records.cost IS '成本，精确到 12 位小数，不舍入；没有定价时为空';
COMMENT ON COLUMN usage_records.attributes IS '种类特有的信息：沙箱的规格与计量区间、搜索后端等';
COMMENT ON COLUMN usage_records.recorded_at IS '入账时间';

-- ---------------------------------------------------------------------------------------------
-- 审计日志
-- ---------------------------------------------------------------------------------------------
CREATE TABLE audit_logs (
    id            text        NOT NULL,
    occurred_at   timestamptz NOT NULL,
    tenant_id     text        NOT NULL,
    project_id    text,
    action        text        NOT NULL CHECK (length(action) BETWEEN 1 AND 128),
    actor_type    text,
    actor_subject text,
    resource_type text,
    resource_id   text,
    session_id    text,
    details       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    recorded_at   timestamptz NOT NULL,
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE INDEX audit_logs_tenant_time_idx ON audit_logs (tenant_id, occurred_at DESC, id DESC);
CREATE INDEX audit_logs_actor_idx ON audit_logs (tenant_id, actor_subject, occurred_at DESC);
CREATE INDEX audit_logs_resource_idx ON audit_logs (tenant_id, resource_type, resource_id, occurred_at DESC);
CREATE INDEX audit_logs_action_idx ON audit_logs (tenant_id, action, occurred_at DESC);
CREATE INDEX audit_logs_session_idx ON audit_logs (tenant_id, session_id, occurred_at DESC) WHERE session_id IS NOT NULL;

COMMENT ON TABLE audit_logs IS '审计日志：只追加，写入只有审计消费者一个入口，按月分区，保留 3 年（架构设计「安全架构」「数据保留」）';
COMMENT ON COLUMN audit_logs.id IS 'outbox 消息 ID：消费者按 (id, occurred_at) 幂等';
COMMENT ON COLUMN audit_logs.occurred_at IS '操作发生的时间，取自消息载荷；分区键';
COMMENT ON COLUMN audit_logs.tenant_id IS '租户 ID（不加外键：审计不随租户删除）';
COMMENT ON COLUMN audit_logs.project_id IS '项目 ID；租户级操作为空';
COMMENT ON COLUMN audit_logs.action IS '动作：<资源>.<动作>，例如 api_key.created、permission.decided';
COMMENT ON COLUMN audit_logs.actor_type IS '操作者类型：console_user、api_key、service';
COMMENT ON COLUMN audit_logs.actor_subject IS '操作者：控制台用户的主体标识、API Key ID 或服务名';
COMMENT ON COLUMN audit_logs.resource_type IS '资源类型，例如 api_key、tool_call、pending_action';
COMMENT ON COLUMN audit_logs.resource_id IS '资源 ID';
COMMENT ON COLUMN audit_logs.session_id IS '相关会话 ID：运行时的审计按会话检索';
COMMENT ON COLUMN audit_logs.details IS '补充信息（JSON 对象），不含凭据';
COMMENT ON COLUMN audit_logs.recorded_at IS '入库时间';

-- 只追加：收回应用角色（以及除表所有者之外的任何角色）对两张表的 UPDATE、DELETE；新建的分区由下面的函数同样收回
DO $$
DECLARE
    grant_row record;
BEGIN
    FOR grant_row IN
        SELECT DISTINCT grantee, table_name FROM information_schema.role_table_grants
         WHERE table_schema = 'cp' AND table_name IN ('usage_records', 'audit_logs')
           AND privilege_type IN ('UPDATE', 'DELETE', 'TRUNCATE') AND grantee <> current_user
    LOOP
        EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON cp.%I FROM %I', grant_row.table_name, grant_row.grantee);
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------------------------
-- 分区的创建与保留：迁移角色拥有、以所有者身份执行（SECURITY DEFINER），应用角色只调用
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION ensure_ledger_partitions(p_month date) RETURNS integer
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = cp, pg_temp
AS $$
DECLARE
    v_start     date        := date_trunc('month', p_month)::date;
    v_from      timestamptz := v_start::timestamp AT TIME ZONE 'UTC';
    v_to        timestamptz := (v_start + interval '1 month')::timestamp AT TIME ZONE 'UTC';
    v_parent    text;
    v_partition text;
    v_grantee   text;
    v_created   integer     := 0;
BEGIN
    -- 多个实例同时创建同一个月的分区时串行，后到的看到已存在
    PERFORM pg_advisory_xact_lock(hashtext('cp.ledger_partitions'));
    FOREACH v_parent IN ARRAY ARRAY['usage_records', 'audit_logs'] LOOP
        v_partition := v_parent || to_char(v_start, '"_p"YYYY_MM');
        IF to_regclass('cp.' || v_partition) IS NULL THEN
            EXECUTE format('CREATE TABLE cp.%I PARTITION OF cp.%I FOR VALUES FROM (%L) TO (%L)',
                           v_partition, v_parent, v_from, v_to);
            -- 默认权限会把 UPDATE、DELETE 授给应用角色，直接访问分区也只能追加
            FOR v_grantee IN
                SELECT DISTINCT grantee FROM information_schema.role_table_grants
                 WHERE table_schema = 'cp' AND table_name = v_partition
                   AND privilege_type IN ('UPDATE', 'DELETE', 'TRUNCATE') AND grantee <> current_user
            LOOP
                EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON cp.%I FROM %I', v_partition, v_grantee);
            END LOOP;
            v_created := v_created + 1;
        END IF;
    END LOOP;
    RETURN v_created;
END;
$$;

COMMENT ON FUNCTION ensure_ledger_partitions(date) IS
    '创建 usage_records 与 audit_logs 在某个 UTC 月份的分区（已存在则跳过），返回新建的个数；分区维护任务提前创建下个月的';

CREATE FUNCTION drop_expired_ledger_partitions() RETURNS integer
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = cp, pg_temp
AS $$
DECLARE
    -- 保留 3 年（架构设计「数据保留」）：截止月份按数据库时间计算，调用方不能借它删除更新的分区
    v_cutoff    date := (date_trunc('month', now() AT TIME ZONE 'UTC') - interval '36 months')::date;
    v_partition record;
    v_month     date;
    v_dropped   integer := 0;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('cp.ledger_partitions'));
    FOR v_partition IN
        SELECT child.relname AS name
          FROM pg_inherits
          JOIN pg_class child ON child.oid = pg_inherits.inhrelid
          JOIN pg_class parent ON parent.oid = pg_inherits.inhparent
          JOIN pg_namespace ns ON ns.oid = parent.relnamespace
         WHERE ns.nspname = 'cp' AND parent.relname IN ('usage_records', 'audit_logs')
    LOOP
        IF v_partition.name ~ '_p[0-9]{4}_[0-9]{2}$' THEN
            v_month := to_date(right(v_partition.name, 7), 'YYYY_MM');
            IF v_month < v_cutoff THEN
                EXECUTE format('DROP TABLE cp.%I', v_partition.name);
                v_dropped := v_dropped + 1;
            END IF;
        END IF;
    END LOOP;
    RETURN v_dropped;
END;
$$;

COMMENT ON FUNCTION drop_expired_ledger_partitions() IS
    '删除早于 36 个月的 usage_records 与 audit_logs 分区（按数据库时间），返回删除的个数';

-- ---------------------------------------------------------------------------------------------
-- 预算
-- ---------------------------------------------------------------------------------------------
CREATE TABLE budgets (
    id                text        PRIMARY KEY,
    tenant_id         text        NOT NULL REFERENCES tenants (id),
    level             text        NOT NULL CHECK (level IN ('tenant', 'project', 'api_key', 'session')),
    scope_id          text,
    project_id        text        REFERENCES projects (id),
    soft_limit_tokens bigint      CHECK (soft_limit_tokens >= 0),
    hard_limit_tokens bigint      CHECK (hard_limit_tokens >= 0),
    created_by        text        NOT NULL,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    CHECK (level <> 'tenant' OR scope_id IS NULL),
    CHECK (soft_limit_tokens IS NULL OR hard_limit_tokens IS NULL OR soft_limit_tokens <= hard_limit_tokens)
);

CREATE UNIQUE INDEX budgets_scope_uq ON budgets (tenant_id, level, coalesce(scope_id, '*'));
CREATE INDEX budgets_project_idx ON budgets (project_id) WHERE project_id IS NOT NULL;

COMMENT ON TABLE budgets IS '四级预算（架构设计「计量与预算」，ADR-0048）：单位 token；没有设置的级别硬阈值为 0（新租户默认零预算）';
COMMENT ON COLUMN budgets.id IS '预算 ID（bdg_…）';
COMMENT ON COLUMN budgets.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN budgets.level IS '级别：tenant、project、api_key、session';
COMMENT ON COLUMN budgets.scope_id IS '范围 ID（项目、API Key 或会话）；为空表示该级所有范围的默认值，租户级总为空';
COMMENT ON COLUMN budgets.project_id IS '范围所在的项目：项目级为该项目，API Key 级为 Key 所在项目；其余为空';
COMMENT ON COLUMN budgets.soft_limit_tokens IS '软阈值（每周期的 token）：越过时告警；为空表示不告警';
COMMENT ON COLUMN budgets.hard_limit_tokens IS '硬阈值（每周期的 token）：达到后拒绝模型调用；为空表示不限';
COMMENT ON COLUMN budgets.created_by IS '创建者的主体标识';
COMMENT ON COLUMN budgets.created_at IS '创建时间';
COMMENT ON COLUMN budgets.updated_at IS '最近一次修改时间';

CREATE TABLE budget_usage (
    tenant_id   text        NOT NULL,
    level       text        NOT NULL CHECK (level IN ('tenant', 'project', 'api_key', 'session')),
    scope_id    text        NOT NULL,
    period      text        NOT NULL,
    used_tokens bigint      NOT NULL CHECK (used_tokens >= 0),
    updated_at  timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, level, scope_id, period)
);

CREATE INDEX budget_usage_period_idx ON budget_usage (period, updated_at);

COMMENT ON TABLE budget_usage IS '账本汇总：每级每周期计入预算的 token，与 usage_records 在同一事务累加；对账与软阈值以它为准';
COMMENT ON COLUMN budget_usage.tenant_id IS '租户 ID';
COMMENT ON COLUMN budget_usage.level IS '级别';
COMMENT ON COLUMN budget_usage.scope_id IS '范围 ID（租户级为租户 ID）';
COMMENT ON COLUMN budget_usage.period IS '周期：UTC 的 yyyy-MM，会话为 total';
COMMENT ON COLUMN budget_usage.used_tokens IS '计入预算的 token（模型调用的输入加输出）';
COMMENT ON COLUMN budget_usage.updated_at IS '最近一次累加的时间';

CREATE TABLE budget_alerts (
    id               text        PRIMARY KEY,
    tenant_id        text        NOT NULL,
    level            text        NOT NULL CHECK (level IN ('tenant', 'project', 'api_key', 'session')),
    scope_id         text        NOT NULL,
    period           text        NOT NULL,
    kind             text        NOT NULL CHECK (kind IN ('soft_limit_reached')),
    threshold_tokens bigint      NOT NULL,
    used_tokens      bigint      NOT NULL,
    occurred_at      timestamptz NOT NULL,
    UNIQUE (tenant_id, level, scope_id, period, kind, threshold_tokens)
);

CREATE INDEX budget_alerts_tenant_idx ON budget_alerts (tenant_id, occurred_at DESC, id DESC);

COMMENT ON TABLE budget_alerts IS '预算告警：账本汇总越过软阈值时产生，同一范围、周期与阈值只有一条，供控制台读取';
COMMENT ON COLUMN budget_alerts.id IS '告警 ID（bal_…）';
COMMENT ON COLUMN budget_alerts.tenant_id IS '租户 ID';
COMMENT ON COLUMN budget_alerts.level IS '级别';
COMMENT ON COLUMN budget_alerts.scope_id IS '范围 ID';
COMMENT ON COLUMN budget_alerts.period IS '周期';
COMMENT ON COLUMN budget_alerts.kind IS '告警种类：soft_limit_reached';
COMMENT ON COLUMN budget_alerts.threshold_tokens IS '越过的阈值';
COMMENT ON COLUMN budget_alerts.used_tokens IS '越过时的账本汇总';
COMMENT ON COLUMN budget_alerts.occurred_at IS '越过阈值的那条用量的发生时间';

CREATE TABLE budget_corrections (
    id                bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         text        NOT NULL,
    level             text        NOT NULL,
    scope_id          text        NOT NULL,
    period            text        NOT NULL,
    observed_tokens   bigint      NOT NULL,
    inflight_tokens   bigint      NOT NULL,
    ledger_tokens     bigint      NOT NULL,
    correction_tokens bigint      NOT NULL,
    reconciled_at     timestamptz NOT NULL
);

CREATE INDEX budget_corrections_tenant_idx ON budget_corrections (tenant_id, reconciled_at);

COMMENT ON TABLE budget_corrections IS '对账修正记录：Redis 计数与"账本加未过期预扣"不一致时的修正量（ADR-0048 第 5 条）';
COMMENT ON COLUMN budget_corrections.tenant_id IS '租户 ID';
COMMENT ON COLUMN budget_corrections.level IS '级别';
COMMENT ON COLUMN budget_corrections.scope_id IS '范围 ID';
COMMENT ON COLUMN budget_corrections.period IS '周期';
COMMENT ON COLUMN budget_corrections.observed_tokens IS '对账时 Redis 中的计数';
COMMENT ON COLUMN budget_corrections.inflight_tokens IS '对账时未过期、未结算的预扣';
COMMENT ON COLUMN budget_corrections.ledger_tokens IS '账本汇总';
COMMENT ON COLUMN budget_corrections.correction_tokens IS '修正量：账本加预扣减去 Redis 计数';
COMMENT ON COLUMN budget_corrections.reconciled_at IS '对账时间';

-- ---------------------------------------------------------------------------------------------
-- Stream 死信
-- ---------------------------------------------------------------------------------------------
CREATE TABLE stream_dead_letters (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    stream      text        NOT NULL,
    record_id   text        NOT NULL,
    message_id  text,
    payload     text,
    error       text        NOT NULL,
    created_at  timestamptz NOT NULL
);

CREATE INDEX stream_dead_letters_time_idx ON stream_dead_letters (stream, created_at);

COMMENT ON TABLE stream_dead_letters IS '用量与审计消费者无法处理的消息（ADR-0048 第 7 条），等人工处理后重放';
COMMENT ON COLUMN stream_dead_letters.stream IS 'Stream 的键：usage 或 audit';
COMMENT ON COLUMN stream_dead_letters.record_id IS 'Redis 条目 ID';
COMMENT ON COLUMN stream_dead_letters.message_id IS 'outbox 消息 ID';
COMMENT ON COLUMN stream_dead_letters.payload IS '消息载荷原文';
COMMENT ON COLUMN stream_dead_letters.error IS '处理失败的原因';
COMMENT ON COLUMN stream_dead_letters.created_at IS '转入死信的时间';

-- ---------------------------------------------------------------------------------------------
-- outbox 的认领与失败计数（ADR-0048 第 8 条），与 meridian-persistence 的 outbox.sql 一致
-- ---------------------------------------------------------------------------------------------
ALTER TABLE outbox ADD COLUMN claimed_until timestamptz;
ALTER TABLE outbox ADD COLUMN attempts integer NOT NULL DEFAULT 0;

COMMENT ON COLUMN outbox.claimed_until IS 'Relay 的认领截止时间：认领后在事务外发布，过期未标记的由任一实例重新认领（ADR-0048）';
COMMENT ON COLUMN outbox.attempts IS '发布失败的次数；达到上限后不再自动投递，等人工处理';
