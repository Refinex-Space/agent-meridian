-- runtime 的核心表（架构设计「数据架构」中「核心表」的 runtime 部分），包括后续任务才使用的表，避免频繁改表。
--
-- 对象建在 Flyway 的默认 schema rt 中（迁移约定见 meridian-persistence 的 MigrationConventions），由迁移角色
-- meridian_migrator 执行；应用以只有 DML 的 meridian_app 运行，权限来自初始化脚本中的 ALTER DEFAULT PRIVILEGES。
-- 除 tenant_concurrency、outbox、idempotency_keys 外，业务表都带 tenant_id 与 project_id。
-- 所有时间列由应用按注入的 Clock 写入，不使用数据库的 now()（ADR-0027）。

-- ---------------------------------------------------------------------------------------------
-- 会话
-- ---------------------------------------------------------------------------------------------
CREATE TABLE sessions (
    id                   text           PRIMARY KEY,
    tenant_id            text           NOT NULL,
    project_id           text           NOT NULL,
    agent_version_id     text           NOT NULL,
    environment_id       text           NOT NULL,
    environment_revision bigint         NOT NULL CHECK (environment_revision >= 1),
    end_user_id          text,
    end_user_attributes  jsonb          NOT NULL DEFAULT '{}'::jsonb,
    interaction          text           NOT NULL DEFAULT 'attended'
                                        CHECK (interaction IN ('attended', 'unattended')),
    status               text           NOT NULL DEFAULT 'idle'
                                        CHECK (status IN ('idle', 'running', 'requires_action', 'rescheduled', 'terminated')),
    metadata             jsonb          NOT NULL DEFAULT '{}'::jsonb,
    custom_tools         jsonb          NOT NULL DEFAULT '[]'::jsonb,
    last_seq             bigint         NOT NULL DEFAULT 0 CHECK (last_seq >= 0),
    sandbox_state        jsonb,
    sandbox_pod_ip       text,
    sandbox_last_used_at timestamptz,
    budget_limit         numeric(20, 6) CHECK (budget_limit >= 0),
    last_activity_at     timestamptz    NOT NULL,
    events_archive_key   text,
    events_archived_at   timestamptz,
    terminated_at        timestamptz,
    created_at           timestamptz    NOT NULL,
    updated_at           timestamptz    NOT NULL
);

-- 会话列表按项目游标分页（ID 为 ULID，按时间有序）
CREATE INDEX sessions_project_idx ON sessions (tenant_id, project_id, id);
-- 事件归档扫描 90 天无活动的会话（架构设计「数据保留」）
CREATE INDEX sessions_activity_idx ON sessions (last_activity_at) WHERE events_archive_key IS NULL;
-- 沙箱空闲回收扫描持有沙箱的会话
CREATE INDEX sessions_sandbox_idle_idx ON sessions (sandbox_last_used_at) WHERE sandbox_pod_ip IS NOT NULL;

COMMENT ON TABLE sessions IS '会话：绑定一个智能体版本与一个环境修订的有状态实例';
COMMENT ON COLUMN sessions.id IS '会话 ID（ses_…）';
COMMENT ON COLUMN sessions.tenant_id IS '租户 ID（ten_…）';
COMMENT ON COLUMN sessions.project_id IS '项目 ID（prj_…），隔离与授权的基本单位';
COMMENT ON COLUMN sessions.agent_version_id IS '绑定的智能体版本 ID（agv_…），创建后不变';
COMMENT ON COLUMN sessions.environment_id IS '绑定的环境 ID（env_…）';
COMMENT ON COLUMN sessions.environment_revision IS '创建会话时固化的环境修订号，从 1 开始';
COMMENT ON COLUMN sessions.end_user_id IS '业务系统的终端用户标识；为空表示没有终端用户';
COMMENT ON COLUMN sessions.end_user_attributes IS '终端用户属性（JSON 对象），例如知识库检索用的 acl_tags';
COMMENT ON COLUMN sessions.interaction IS '值守方式：attended 有人值守（默认），unattended 无人值守';
COMMENT ON COLUMN sessions.status IS '对外可见的会话状态（架构设计「状态机」），由 session.status_* 事件在同一事务中推进';
COMMENT ON COLUMN sessions.metadata IS '调用方附加的元数据（JSON 对象），平台不解释';
COMMENT ON COLUMN sessions.custom_tools IS '创建会话时声明的自定义工具 schema（JSON 数组）';
COMMENT ON COLUMN sessions.last_seq IS '已分配的最大事件序号：写事件时 last_seq + 1 得到新事件的 seq，行锁保证会话内严格递增且连续';
COMMENT ON COLUMN sessions.sandbox_state IS '会话沙箱的序列化状态；为空表示没有沙箱或已回收';
COMMENT ON COLUMN sessions.sandbox_pod_ip IS '会话沙箱 Pod 的 IP，出网代理据此按源 IP 鉴权；为空表示当前没有沙箱 Pod';
COMMENT ON COLUMN sessions.sandbox_last_used_at IS '沙箱最近一次被轮次使用的时间，空闲回收据此判断';
COMMENT ON COLUMN sessions.budget_limit IS '会话级预算的硬阈值（金额，币种与价格表一致）；为空表示不设会话级预算';
COMMENT ON COLUMN sessions.last_activity_at IS '最近一次写入事件的时间，事件归档据此判断 90 天无活动';
COMMENT ON COLUMN sessions.events_archive_key IS '归档事件文件在对象存储中的键；为空表示事件未归档';
COMMENT ON COLUMN sessions.events_archived_at IS '事件归档时间；为空表示未归档';
COMMENT ON COLUMN sessions.terminated_at IS '归档或删除的时间；为空表示会话未终止';
COMMENT ON COLUMN sessions.created_at IS '创建时间';
COMMENT ON COLUMN sessions.updated_at IS '最近一次修改时间';

-- ---------------------------------------------------------------------------------------------
-- 线程
-- ---------------------------------------------------------------------------------------------
CREATE TABLE threads (
    id                 text        PRIMARY KEY,
    tenant_id          text        NOT NULL,
    project_id         text        NOT NULL,
    session_id         text        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    parent_thread_id   text        REFERENCES threads (id) ON DELETE CASCADE,
    parent_tool_use_id text,
    agent_name         text        NOT NULL,
    status             text        NOT NULL DEFAULT 'idle'
                                   CHECK (status IN ('idle', 'running', 'requires_action', 'rescheduled', 'terminated')),
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    -- 子线程由 spawn_subagent 的一次工具调用产生，父线程与工具调用 ID 同时存在或同时为空
    CHECK ((parent_thread_id IS NULL) = (parent_tool_use_id IS NULL))
);

CREATE INDEX threads_session_idx ON threads (session_id, id);
-- 每个会话恰好有一个主线程
CREATE UNIQUE INDEX threads_main_uidx ON threads (session_id) WHERE parent_thread_id IS NULL;
-- 级联删除父线程时按父线程查找子线程
CREATE INDEX threads_parent_idx ON threads (parent_thread_id) WHERE parent_thread_id IS NOT NULL;

COMMENT ON TABLE threads IS '线程：会话中的一条执行线，主智能体是主线程，每次拉起子智能体产生一条子线程';
COMMENT ON COLUMN threads.id IS '线程 ID（thr_…），也是 AgentScope 的 RuntimeContext.sessionId（架构设计「身份映射」）';
COMMENT ON COLUMN threads.tenant_id IS '租户 ID';
COMMENT ON COLUMN threads.project_id IS '项目 ID';
COMMENT ON COLUMN threads.session_id IS '所属会话';
COMMENT ON COLUMN threads.parent_thread_id IS '父线程 ID；主线程为空';
COMMENT ON COLUMN threads.parent_tool_use_id IS '拉起本子线程的 spawn_subagent 工具调用 ID；主线程为空';
COMMENT ON COLUMN threads.agent_name IS '执行本线程的智能体名：主线程为智能体名，子线程为子智能体名';
COMMENT ON COLUMN threads.status IS '线程状态，取值同会话状态；由状态事件在同一事务中推进';
COMMENT ON COLUMN threads.created_at IS '创建时间';
COMMENT ON COLUMN threads.updated_at IS '最近一次修改时间';

-- ---------------------------------------------------------------------------------------------
-- 轮次：队列与租约（架构设计「一轮执行的生命周期」「租约与 fencing」）
-- ---------------------------------------------------------------------------------------------
CREATE TABLE turns (
    id                     text        PRIMARY KEY,
    tenant_id              text        NOT NULL,
    project_id             text        NOT NULL,
    session_id             text        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    thread_id              text        NOT NULL REFERENCES threads (id) ON DELETE CASCADE,
    kind                   text        NOT NULL
                                       CHECK (kind IN ('messages', 'confirmations', 'custom_tool_results', 'recovery')),
    status                 text        NOT NULL
                                       CHECK (status IN ('queued', 'running', 'awaiting_action', 'completed',
                                                         'interrupted', 'failed', 'cancelled')),
    priority               integer     NOT NULL DEFAULT 0,
    not_before             timestamptz NOT NULL,
    lease_owner            text,
    lease_epoch            bigint      NOT NULL DEFAULT 0 CHECK (lease_epoch >= 0),
    lease_expires_at       timestamptz,
    claim_count            integer     NOT NULL DEFAULT 0 CHECK (claim_count >= 0),
    recovery_attempt       integer     NOT NULL DEFAULT 0 CHECK (recovery_attempt >= 0),
    interrupt_requested_at timestamptz,
    stop_reason            text        CHECK (stop_reason IN ('end_turn', 'interrupted', 'max_turns', 'budget_exceeded',
                                                              'tools_denied', 'error')),
    created_at             timestamptz NOT NULL,
    claimed_at             timestamptz,
    finished_at            timestamptz,
    updated_at             timestamptz NOT NULL,
    -- 执行中的轮次一定有持有者、到期时间与大于 0 的 epoch
    CHECK (status <> 'running' OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL AND lease_epoch > 0))
);

-- 每个线程同时最多一个未结束的轮次，插入用 ON CONFLICT DO NOTHING（架构设计「核心表」）
CREATE UNIQUE INDEX turns_thread_active_uidx ON turns (thread_id) WHERE status IN ('queued', 'running', 'awaiting_action');
-- 认领：按优先级与入队时间取可执行的轮次（认领 SQL 的执行计划见 meridian-runtime 的 CLAUDE.md）
CREATE INDEX turns_queued_idx ON turns (priority, created_at) WHERE status = 'queued';
-- 回收器：找出租约过期的执行中轮次
CREATE INDEX turns_lease_expiry_idx ON turns (lease_expires_at) WHERE status = 'running';
-- 节点下线：找出本节点持有的轮次
CREATE INDEX turns_lease_owner_idx ON turns (lease_owner) WHERE status = 'running';
CREATE INDEX turns_thread_idx ON turns (thread_id, created_at);
CREATE INDEX turns_session_idx ON turns (session_id);

COMMENT ON TABLE turns IS '轮次：线程上的一次执行，同时是调度队列与租约的载体';
COMMENT ON COLUMN turns.id IS '轮次 ID（trn_…）';
COMMENT ON COLUMN turns.tenant_id IS '租户 ID，认领时与 tenant_concurrency 关联';
COMMENT ON COLUMN turns.project_id IS '项目 ID';
COMMENT ON COLUMN turns.session_id IS '所属会话';
COMMENT ON COLUMN turns.thread_id IS '执行的线程';
COMMENT ON COLUMN turns.kind IS '轮次类型，与 TurnInput 的四种输入一一对应：messages、confirmations、custom_tool_results、recovery';
COMMENT ON COLUMN turns.status IS '轮次状态：queued 待认领，running 执行中，awaiting_action 等待调用方回应，其余为终态';
COMMENT ON COLUMN turns.priority IS '优先级，数值小的先被认领，默认 0';
COMMENT ON COLUMN turns.not_before IS '最早可被认领的时间';
COMMENT ON COLUMN turns.lease_owner IS '持有租约的 runtime 节点 ID；不在执行中时为最近一次的持有者，从未被认领时为空。租约条件总是同时要求 status = running';
COMMENT ON COLUMN turns.lease_epoch IS '租约 epoch：每次认领加一，所有受租约约束的写入都校验它（架构设计「租约与 fencing」）；置回队列时保留失败那次执行的值';
COMMENT ON COLUMN turns.lease_expires_at IS '租约到期时间：认领与续租时设为当前时间加 30 秒；不在执行中时为空';
COMMENT ON COLUMN turns.claim_count IS '被认领的次数，只用于观测';
COMMENT ON COLUMN turns.recovery_attempt IS '恢复次数：租约过期与可重试错误置回队列时加一，节点优雅下线不变；达到 2 次后以 error 结束';
COMMENT ON COLUMN turns.interrupt_requested_at IS '收到 user.interrupt 的时间；Worker 续租时读取，为空表示没有中断请求';
COMMENT ON COLUMN turns.stop_reason IS '结束原因，即 session.status_idle 的 stop_reason；未结束或不以 idle 结束时为空';
COMMENT ON COLUMN turns.created_at IS '入队时间';
COMMENT ON COLUMN turns.claimed_at IS '最近一次被认领的时间；从未被认领时为空';
COMMENT ON COLUMN turns.finished_at IS '进入终态或 awaiting_action 的时间；为空表示未结束';
COMMENT ON COLUMN turns.updated_at IS '最近一次修改时间';

-- ---------------------------------------------------------------------------------------------
-- 轮次输入
-- ---------------------------------------------------------------------------------------------
CREATE TABLE turn_inputs (
    turn_id    text        PRIMARY KEY REFERENCES turns (id) ON DELETE CASCADE,
    tenant_id  text        NOT NULL,
    project_id text        NOT NULL,
    payload    jsonb       NOT NULL,
    created_at timestamptz NOT NULL
);

COMMENT ON TABLE turn_inputs IS '每轮的完整输入，与轮次在同一事务中写入，崩溃恢复时用于重放';
COMMENT ON COLUMN turn_inputs.turn_id IS '轮次 ID';
COMMENT ON COLUMN turn_inputs.tenant_id IS '租户 ID';
COMMENT ON COLUMN turn_inputs.project_id IS '项目 ID';
COMMENT ON COLUMN turn_inputs.payload IS 'TurnInput 的 JSON：具体类型由 turns.kind 决定，字段 snake_case（ADR-0027）';
COMMENT ON COLUMN turn_inputs.created_at IS '写入时间';

-- ---------------------------------------------------------------------------------------------
-- 租户并发计数
-- ---------------------------------------------------------------------------------------------
CREATE TABLE tenant_concurrency (
    tenant_id   text        PRIMARY KEY,
    running     integer     NOT NULL DEFAULT 0 CHECK (running >= 0),
    max_running integer     NOT NULL CHECK (max_running >= 0),
    updated_at  timestamptz NOT NULL
);

COMMENT ON TABLE tenant_concurrency IS '租户并发计数：认领时在同一事务中占用名额，轮次结束或置回队列时释放；为租户第一次插入轮次时创建';
COMMENT ON COLUMN tenant_concurrency.tenant_id IS '租户 ID';
COMMENT ON COLUMN tenant_concurrency.running IS '正在执行的轮次数';
COMMENT ON COLUMN tenant_concurrency.max_running IS '并发上限：租户配额，未配置时取全局默认值；running 达到它时该租户的轮次不被认领';
COMMENT ON COLUMN tenant_concurrency.updated_at IS '最近一次修改时间';

-- ---------------------------------------------------------------------------------------------
-- 会话事件：对外的唯一事实来源（架构原则第 3 条），按 session_id 哈希分 16 个分区
-- ---------------------------------------------------------------------------------------------
CREATE TABLE session_events (
    session_id text        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    seq        bigint      NOT NULL CHECK (seq >= 1),
    id         text        NOT NULL,
    tenant_id  text        NOT NULL,
    project_id text        NOT NULL,
    thread_id  text,
    turn_id    text,
    type       text        NOT NULL,
    data       jsonb       NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (session_id, seq),
    -- 分区表的唯一约束必须包含分区键；按事件 ID 查找时同时给出会话
    CONSTRAINT session_events_id_key UNIQUE (session_id, id)
) PARTITION BY HASH (session_id);

DO $$
BEGIN
    FOR remainder IN 0..15 LOOP
        EXECUTE format(
                'CREATE TABLE session_events_p%s PARTITION OF session_events FOR VALUES WITH (MODULUS 16, REMAINDER %s)',
                lpad(remainder::text, 2, '0'), remainder);
        EXECUTE format(
                'COMMENT ON TABLE session_events_p%s IS %L',
                lpad(remainder::text, 2, '0'), 'session_events 的哈希分区，余数 ' || remainder || '（共 16 个）');
    END LOOP;
END
$$;

-- 线程流与 ?threads=main 按线程补发、对账
CREATE INDEX session_events_thread_idx ON session_events (session_id, thread_id, seq);

COMMENT ON TABLE session_events IS '会话事件：会话里发生的一切，seq 在会话内严格递增且连续；只在流上出现的事件不写入';
COMMENT ON COLUMN session_events.session_id IS '所属会话，也是分区键';
COMMENT ON COLUMN session_events.seq IS '会话内序号，由 sessions.last_seq 加一分配，从 1 开始';
COMMENT ON COLUMN session_events.id IS '事件 ID（evt_…）';
COMMENT ON COLUMN session_events.tenant_id IS '租户 ID';
COMMENT ON COLUMN session_events.project_id IS '项目 ID';
COMMENT ON COLUMN session_events.thread_id IS '事件所属线程（子线程事件为子线程）；为空表示会话级事件';
COMMENT ON COLUMN session_events.turn_id IS '产生事件的轮次；为空表示不属于某一轮，例如调用方发送的 user.* 事件';
COMMENT ON COLUMN session_events.type IS '事件类型，取值见架构设计「事件」一节的事件类型清单';
COMMENT ON COLUMN session_events.data IS '事件内容（JSON，字段 snake_case），即事件信封的 data（ADR-0027）';
COMMENT ON COLUMN session_events.created_at IS '写入时间';

-- ---------------------------------------------------------------------------------------------
-- 收件箱
-- ---------------------------------------------------------------------------------------------
CREATE TABLE inbox_messages (
    id               text        PRIMARY KEY,
    tenant_id        text        NOT NULL,
    project_id       text        NOT NULL,
    session_id       text        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    thread_id        text        NOT NULL REFERENCES threads (id) ON DELETE CASCADE,
    event_id         text        NOT NULL,
    delivery         text        NOT NULL CHECK (delivery IN ('queue', 'steer')),
    status           text        NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'consumed')),
    consumed_turn_id text,
    consumed_epoch   bigint      CHECK (consumed_epoch >= 1),
    created_at       timestamptz NOT NULL,
    consumed_at      timestamptz
);

-- 取线程待投递的消息
CREATE INDEX inbox_messages_pending_idx ON inbox_messages (thread_id, created_at) WHERE status = 'pending';
-- 恢复时找出被失败那次执行消费的消息
CREATE INDEX inbox_messages_consumed_idx ON inbox_messages (consumed_turn_id, consumed_epoch)
    WHERE consumed_turn_id IS NOT NULL;
CREATE INDEX inbox_messages_session_idx ON inbox_messages (session_id);

COMMENT ON TABLE inbox_messages IS '待投递的用户消息（架构设计「消息投递」）';
COMMENT ON COLUMN inbox_messages.id IS '收件箱记录 ID（ULID）';
COMMENT ON COLUMN inbox_messages.tenant_id IS '租户 ID';
COMMENT ON COLUMN inbox_messages.project_id IS '项目 ID';
COMMENT ON COLUMN inbox_messages.session_id IS '所属会话';
COMMENT ON COLUMN inbox_messages.thread_id IS '投递目标线程';
COMMENT ON COLUMN inbox_messages.event_id IS '对应的 user.message 事件 ID；不建外键，事件归档后仍保留收件箱记录';
COMMENT ON COLUMN inbox_messages.delivery IS '投递方式：queue 本轮结束后成为下一轮，steer 注入当前轮';
COMMENT ON COLUMN inbox_messages.status IS 'pending 待投递，consumed 已被某次执行消费';
COMMENT ON COLUMN inbox_messages.consumed_turn_id IS '消费它的轮次；未消费时为空';
COMMENT ON COLUMN inbox_messages.consumed_epoch IS '消费它的那次执行的 lease_epoch，恢复时据此重新投递；未消费时为空';
COMMENT ON COLUMN inbox_messages.created_at IS '写入时间';
COMMENT ON COLUMN inbox_messages.consumed_at IS '被消费的时间；未消费时为空';

-- ---------------------------------------------------------------------------------------------
-- 智能体状态（FencedAgentStateStore 的存储）
-- ---------------------------------------------------------------------------------------------
CREATE TABLE agent_states (
    user_id       text        NOT NULL,
    thread_id     text        NOT NULL REFERENCES threads (id) ON DELETE CASCADE,
    state_key     text        NOT NULL,
    tenant_id     text        NOT NULL,
    project_id    text        NOT NULL,
    state         jsonb       NOT NULL,
    version       bigint      NOT NULL CHECK (version >= 1),
    content_epoch bigint      CHECK (content_epoch >= 1),
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    PRIMARY KEY (user_id, thread_id, state_key)
);

-- 级联删除线程时按线程查找
CREATE INDEX agent_states_thread_idx ON agent_states (thread_id);

COMMENT ON TABLE agent_states IS 'AgentScope 的智能体状态，键为 (userId, sessionId, key)；每次写入在同一事务中校验租约（架构设计「租约与 fencing」）';
COMMENT ON COLUMN agent_states.user_id IS 'AgentScope 的 userId：{project_id}:{end_user_id}，没有终端用户时为 {project_id}:_';
COMMENT ON COLUMN agent_states.thread_id IS '线程 ID，即 AgentScope 的 sessionId';
COMMENT ON COLUMN agent_states.state_key IS 'AgentScope 的状态键名';
COMMENT ON COLUMN agent_states.tenant_id IS '租户 ID，写入时取自持有租约的轮次';
COMMENT ON COLUMN agent_states.project_id IS '项目 ID，写入时取自持有租约的轮次';
COMMENT ON COLUMN agent_states.state IS '状态 JSON，由 AgentScope 的 Jackson 2 序列化；列表状态为 JSON 数组';
COMMENT ON COLUMN agent_states.version IS '版本号：从 1 开始，每次写入加一，供 saveIfVersion 做乐观并发';
COMMENT ON COLUMN agent_states.content_epoch IS '最近一次内容写入（权限上下文的安装写入除外）时的租约 epoch，崩溃恢复据此选择恢复方式；为空表示还没有内容写入';
COMMENT ON COLUMN agent_states.created_at IS '首次写入时间';
COMMENT ON COLUMN agent_states.updated_at IS '最近一次写入时间';

-- ---------------------------------------------------------------------------------------------
-- 工具调用账本
-- ---------------------------------------------------------------------------------------------
CREATE TABLE tool_invocations (
    id              text        PRIMARY KEY,
    tenant_id       text        NOT NULL,
    project_id      text        NOT NULL,
    session_id      text        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    turn_id         text        NOT NULL REFERENCES turns (id) ON DELETE CASCADE,
    thread_id       text        NOT NULL REFERENCES threads (id) ON DELETE CASCADE,
    lease_epoch     bigint      NOT NULL CHECK (lease_epoch >= 1),
    tool_use_id     text        NOT NULL,
    tool_name       text        NOT NULL,
    side_effect     text        NOT NULL CHECK (side_effect IN ('none', 'idempotent', 'non_idempotent')),
    idempotency_key text,
    status          text        NOT NULL CHECK (status IN ('running', 'succeeded', 'failed', 'denied', 'interrupted')),
    input           jsonb       NOT NULL,
    result_ref      jsonb,
    started_at      timestamptz NOT NULL,
    finished_at     timestamptz,
    -- non_idempotent 调用必须带幂等键（架构设计「平台工具」）
    CHECK (side_effect <> 'non_idempotent' OR idempotency_key IS NOT NULL)
);

-- 恢复说明按轮次读取账本
CREATE INDEX tool_invocations_turn_idx ON tool_invocations (turn_id, started_at);
CREATE INDEX tool_invocations_thread_idx ON tool_invocations (thread_id);
CREATE INDEX tool_invocations_session_idx ON tool_invocations (session_id);

COMMENT ON TABLE tool_invocations IS '工具调用账本：每次工具执行的开始与结束，崩溃恢复据此生成恢复说明';
COMMENT ON COLUMN tool_invocations.id IS '账本记录 ID（tiv_…）';
COMMENT ON COLUMN tool_invocations.tenant_id IS '租户 ID';
COMMENT ON COLUMN tool_invocations.project_id IS '项目 ID';
COMMENT ON COLUMN tool_invocations.session_id IS '所属会话';
COMMENT ON COLUMN tool_invocations.turn_id IS '发起调用的轮次（子线程的调用记在父轮次下）';
COMMENT ON COLUMN tool_invocations.thread_id IS '执行工具的线程（子智能体为子线程）';
COMMENT ON COLUMN tool_invocations.lease_epoch IS '发起调用的那次执行的租约 epoch';
COMMENT ON COLUMN tool_invocations.tool_use_id IS '模型给出的工具调用 ID';
COMMENT ON COLUMN tool_invocations.tool_name IS '工具名';
COMMENT ON COLUMN tool_invocations.side_effect IS '副作用等级：none、idempotent 可以重放，non_idempotent 不能重放';
COMMENT ON COLUMN tool_invocations.idempotency_key IS '幂等键；non_idempotent 必填，其余可为空';
COMMENT ON COLUMN tool_invocations.status IS 'running 执行中（强杀后仍为 running 的视为状态未知），succeeded、failed、denied、interrupted 为结束状态';
COMMENT ON COLUMN tool_invocations.input IS '工具入参（JSON 对象）';
COMMENT ON COLUMN tool_invocations.result_ref IS '结果在对象存储中的引用（key、content_type、size）；为空表示结果不单独保存';
COMMENT ON COLUMN tool_invocations.started_at IS '开始时间，在发起副作用之前写入';
COMMENT ON COLUMN tool_invocations.finished_at IS '结束时间；为空表示未结束';

-- ---------------------------------------------------------------------------------------------
-- 待处理动作
-- ---------------------------------------------------------------------------------------------
CREATE TABLE pending_actions (
    id             text        PRIMARY KEY,
    tenant_id      text        NOT NULL,
    project_id     text        NOT NULL,
    session_id     text        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    turn_id        text        NOT NULL REFERENCES turns (id) ON DELETE CASCADE,
    thread_id      text        NOT NULL REFERENCES threads (id) ON DELETE CASCADE,
    type           text        NOT NULL
                               CHECK (type IN ('tool_confirmation', 'plan_exit', 'custom_tool_result', 'recovery_confirmation')),
    payload        jsonb       NOT NULL,
    status         text        NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'resolved', 'expired')),
    decided_by     text,
    remember_scope text        CHECK (remember_scope IN ('none', 'session', 'project')),
    expires_at     timestamptz NOT NULL,
    created_at     timestamptz NOT NULL,
    resolved_at    timestamptz
);

CREATE INDEX pending_actions_turn_idx ON pending_actions (turn_id);
CREATE INDEX pending_actions_session_idx ON pending_actions (session_id, created_at) WHERE status = 'pending';
-- 超时扫描
CREATE INDEX pending_actions_expiry_idx ON pending_actions (expires_at) WHERE status = 'pending';
CREATE INDEX pending_actions_thread_idx ON pending_actions (thread_id);

COMMENT ON TABLE pending_actions IS '待处理动作：需要调用方回应的事项（架构设计「待处理动作」）';
COMMENT ON COLUMN pending_actions.id IS '动作 ID（act_…）';
COMMENT ON COLUMN pending_actions.tenant_id IS '租户 ID';
COMMENT ON COLUMN pending_actions.project_id IS '项目 ID';
COMMENT ON COLUMN pending_actions.session_id IS '所属会话';
COMMENT ON COLUMN pending_actions.turn_id IS '产生动作的轮次';
COMMENT ON COLUMN pending_actions.thread_id IS '产生动作的线程';
COMMENT ON COLUMN pending_actions.type IS '动作类型：tool_confirmation、plan_exit、custom_tool_result、recovery_confirmation';
COMMENT ON COLUMN pending_actions.payload IS '动作内容（JSON）：相关工具调用、入参等';
COMMENT ON COLUMN pending_actions.status IS 'pending 等待回应，resolved 已回应，expired 已超时';
COMMENT ON COLUMN pending_actions.decided_by IS '做出决定的主体（API Key ID 或控制台用户）；未回应或超时时为空';
COMMENT ON COLUMN pending_actions.remember_scope IS '"记住此决定"的范围：none、session、project；未回应时为空';
COMMENT ON COLUMN pending_actions.expires_at IS '截止时间，默认创建后 30 分钟，超时按动作类型处理';
COMMENT ON COLUMN pending_actions.created_at IS '创建时间';
COMMENT ON COLUMN pending_actions.resolved_at IS '回应或超时的时间；为空表示仍在等待';

-- ---------------------------------------------------------------------------------------------
-- 工作区（none 档，MeridianBaseStore 的存储）
-- ---------------------------------------------------------------------------------------------
CREATE TABLE workspace_entries (
    namespace      text[]      NOT NULL,
    key            text        NOT NULL,
    tenant_id      text        NOT NULL,
    project_id     text        NOT NULL,
    version        bigint      NOT NULL CHECK (version >= 1),
    size           bigint      NOT NULL CHECK (size >= 0),
    inline_content jsonb,
    object_key     text,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    PRIMARY KEY (namespace, key),
    -- 内容要么内联，要么在对象存储，二者恰好一个
    CHECK ((inline_content IS NULL) <> (object_key IS NULL))
);

COMMENT ON TABLE workspace_entries IS '工作区条目：64 KB 以内的内容内联存储，更大的放对象存储（架构设计「存储分工」）';
COMMENT ON COLUMN workspace_entries.namespace IS '命名空间：[tenant_id, project_id, end_user_id, session_id, …]，以租户与项目开头';
COMMENT ON COLUMN workspace_entries.key IS '命名空间内的键';
COMMENT ON COLUMN workspace_entries.tenant_id IS '租户 ID';
COMMENT ON COLUMN workspace_entries.project_id IS '项目 ID';
COMMENT ON COLUMN workspace_entries.version IS '版本号：从 1 开始，每次写入加一，供 putIfVersion 做乐观并发';
COMMENT ON COLUMN workspace_entries.size IS '内容大小（字节）';
COMMENT ON COLUMN workspace_entries.inline_content IS '内联的内容（JSON 对象）；内容在对象存储时为空';
COMMENT ON COLUMN workspace_entries.object_key IS '内容在对象存储中的键；内容内联时为空';
COMMENT ON COLUMN workspace_entries.created_at IS '首次写入时间';
COMMENT ON COLUMN workspace_entries.updated_at IS '最近一次写入时间';

-- ---------------------------------------------------------------------------------------------
-- outbox 与幂等键：复制自 meridian-persistence 的建表片段（outbox.sql、idempotency.sql）
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS outbox (
    id           text        PRIMARY KEY,
    topic        text        NOT NULL,
    payload      jsonb       NOT NULL,
    created_at   timestamptz NOT NULL,
    published_at timestamptz
);

CREATE INDEX IF NOT EXISTS outbox_unpublished_idx ON outbox (id) WHERE published_at IS NULL;

COMMENT ON TABLE outbox IS '待可靠投递的消息：业务事务内写入，OutboxRelay 发布到 Redis Stream 后标记';
COMMENT ON COLUMN outbox.id IS '消息 ID（ULID），消费方据此幂等';
COMMENT ON COLUMN outbox.topic IS '主题，即目标 Redis Stream 的键，例如 usage、audit';
COMMENT ON COLUMN outbox.payload IS '消息载荷（JSON，字段 snake_case），自带租户与项目';
COMMENT ON COLUMN outbox.created_at IS '写入时间';
COMMENT ON COLUMN outbox.published_at IS '发布时间；为空表示尚未发布';

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

CREATE INDEX IF NOT EXISTS idempotency_keys_expires_idx ON idempotency_keys (expires_at);

COMMENT ON TABLE idempotency_keys IS 'Idempotency-Key 的首次请求与结果，24 小时内同键同请求体返回首次结果';
COMMENT ON COLUMN idempotency_keys.tenant_id IS '租户 ID';
COMMENT ON COLUMN idempotency_keys.principal IS '调用方身份，例如 API Key ID；同一租户内不同调用方的同名键互不影响';
COMMENT ON COLUMN idempotency_keys.idempotency_key IS '调用方提供的 Idempotency-Key 请求头';
COMMENT ON COLUMN idempotency_keys.request_hash IS '请求指纹（方法、路径、请求体的 SHA-256），同键不同指纹视为冲突';
COMMENT ON COLUMN idempotency_keys.status IS 'in_progress：首次请求处理中；completed：已保存结果';
COMMENT ON COLUMN idempotency_keys.response_status IS '首次请求的 HTTP 状态码，completed 时有值';
COMMENT ON COLUMN idempotency_keys.response_body IS '首次请求的响应体，completed 时有值，可为空';
COMMENT ON COLUMN idempotency_keys.created_at IS '首次请求开始处理的时间';
COMMENT ON COLUMN idempotency_keys.expires_at IS '过期时间，过期后同一个键可以重新使用';
