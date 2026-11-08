-- 消息投递、待处理动作与权限合成（T08）：待处理动作的回应与消费记录，以及权限规则（租户、项目、会话三层）。

-- 回应事件或超时给出的结论，生成恢复轮时据此构造输入；consumed_turn_id 记录把结论交给智能体的那一轮。同一批动作既有待确认
-- 调用又有自定义工具时先生成确认轮，自定义工具的结果留到智能体再次请求时才交出（架构设计「待处理动作」）
ALTER TABLE pending_actions ADD COLUMN response jsonb;
ALTER TABLE pending_actions ADD COLUMN consumed_turn_id text;

COMMENT ON COLUMN pending_actions.response IS '回应或超时的结论（JSON）：tool_confirmation、plan_exit 为 approved、input、deny_reason；custom_tool_result 为 content、error；recovery_confirmation 为 decision。还在等待时为空';
COMMENT ON COLUMN pending_actions.consumed_turn_id IS '把结论交给智能体的恢复轮；为空表示结论尚未交出。不建外键，轮次随会话删除';
COMMENT ON COLUMN pending_actions.status IS 'pending 等待回应，resolved 已回应（含平台自动拒绝），expired 已超时（结论按动作类型生成）或随会话终止作废（结论为空）';

-- 已回应、尚未交出的自定义工具结果，按线程与工具调用查找
CREATE INDEX pending_actions_unconsumed_idx ON pending_actions (thread_id, tool_use_id)
    WHERE consumed_turn_id IS NULL AND response IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- 权限规则（开发实现）：租户策略、项目策略与"记住此决定"写入的项目级、会话级规则。T15b 起租户与项目策略由 control-plane
-- 下发，项目级"记住此决定"写入 control-plane；会话级规则一直属于 runtime
-- ---------------------------------------------------------------------------------------------
CREATE TABLE permission_rules (
    id               text        PRIMARY KEY,
    tenant_id        text        NOT NULL,
    project_id       text,
    session_id       text        REFERENCES sessions (id) ON DELETE CASCADE,
    layer            text        NOT NULL CHECK (layer IN ('tenant', 'project', 'session')),
    tool_name        text        NOT NULL,
    rule_content     text,
    behavior         text        NOT NULL CHECK (behavior IN ('allow', 'deny', 'ask')),
    source_action_id text,
    created_by       text,
    created_at       timestamptz NOT NULL,
    -- 租户层不属于任何项目；会话层属于一个会话，其余两层不属于会话
    CHECK ((layer = 'tenant') = (project_id IS NULL)),
    CHECK ((layer = 'session') = (session_id IS NOT NULL))
);

CREATE INDEX permission_rules_scope_idx ON permission_rules (tenant_id, project_id, layer);
CREATE INDEX permission_rules_session_idx ON permission_rules (session_id) WHERE session_id IS NOT NULL;

COMMENT ON TABLE permission_rules IS '权限规则：五层合成中的租户、项目、会话三层（架构设计「权限判定」）；平台安全规则写在代码里，智能体层随智能体版本';
COMMENT ON COLUMN permission_rules.id IS '规则 ID（ULID）';
COMMENT ON COLUMN permission_rules.tenant_id IS '租户 ID';
COMMENT ON COLUMN permission_rules.project_id IS '项目 ID；租户层为空';
COMMENT ON COLUMN permission_rules.session_id IS '会话 ID；只有会话层不为空';
COMMENT ON COLUMN permission_rules.layer IS '所属层：tenant、project、session';
COMMENT ON COLUMN permission_rules.tool_name IS '工具名';
COMMENT ON COLUMN permission_rules.rule_content IS '规则内容，语法由工具的 matchRule 解释；为空表示整工具规则';
COMMENT ON COLUMN permission_rules.behavior IS '命中后的结果：allow、deny、ask';
COMMENT ON COLUMN permission_rules.source_action_id IS '由"记住此决定"写入时为对应的待处理动作 ID；其他来源为空';
COMMENT ON COLUMN permission_rules.created_by IS '写入它的主体（API Key ID 或控制台用户）；未知时为空';
COMMENT ON COLUMN permission_rules.created_at IS '写入时间';
