-- 控制台需要的查询（T19b，ADR-0053）：会话记下智能体 ID 并按智能体、状态筛选；项目级待处理动作列表；按智能体的失败率小时汇总。
--
-- 新索引只覆盖控制台查询的范围，主路径的额外写入是会话行与轮次行更新时的索引维护：部分索引只收录少量行（执行中的会话、失败的
-- 轮次、等待回应的动作）。失败率不在轮次结束时累加（同一智能体的轮次结束会争用同一行），由后台作业按水位汇总。

-- 会话绑定的智能体 ID（agt_…），创建时由解析出的版本取得。之前创建的会话为空：智能体在 control-plane，迁移无法回填
ALTER TABLE sessions ADD COLUMN agent_id text;

COMMENT ON COLUMN sessions.agent_id IS '绑定的智能体 ID（agt_…），创建后不变；T19b 之前创建的会话为空';

-- GET /v1/sessions?agent_id= 按智能体游标分页；会话统计按智能体计数使用中的会话
CREATE INDEX sessions_agent_idx ON sessions (tenant_id, project_id, agent_id, id)
    WHERE deleted_at IS NULL AND agent_id IS NOT NULL;
-- 会话统计计数执行中与等待回应的会话、按这些状态筛选列表：只收录这三种状态的会话，条目随状态进出
CREATE INDEX sessions_live_status_idx ON sessions (tenant_id, project_id, status, id)
    WHERE status IN ('running', 'requires_action', 'rescheduled');

-- GET /v1/pending-actions 按项目游标分页（新的在前），等待回应的另有部分索引供列表与计数
CREATE INDEX pending_actions_project_idx ON pending_actions (tenant_id, project_id, id);
CREATE INDEX pending_actions_project_pending_idx ON pending_actions (tenant_id, project_id, id) WHERE status = 'pending';

-- 汇总作业按结束时间扫描水位之后结束的轮次
CREATE INDEX turns_finished_idx ON turns (finished_at) WHERE status IN ('completed', 'interrupted', 'failed');
-- 会话统计的最近错误：只收录失败的轮次
CREATE INDEX turns_failed_idx ON turns (tenant_id, project_id, finished_at DESC) WHERE status = 'failed';

-- ---------------------------------------------------------------------------------------------
-- 按智能体的轮次小时汇总
-- ---------------------------------------------------------------------------------------------
CREATE TABLE agent_turn_stats (
    tenant_id    text        NOT NULL,
    project_id   text        NOT NULL,
    agent_id     text        NOT NULL,
    hour_start   timestamptz NOT NULL,
    turns        bigint      NOT NULL DEFAULT 0 CHECK (turns >= 0),
    failed_turns bigint      NOT NULL DEFAULT 0 CHECK (failed_turns >= 0 AND failed_turns <= turns),
    updated_at   timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, project_id, agent_id, hour_start)
);

-- 清理过期的小时行
CREATE INDEX agent_turn_stats_hour_idx ON agent_turn_stats (hour_start);

COMMENT ON TABLE agent_turn_stats IS '按项目、智能体、小时（UTC 整点）汇总结束的轮次，会话统计的失败率读它；由 TurnStatsRollup 每分钟累加，保留 8 天';
COMMENT ON COLUMN agent_turn_stats.tenant_id IS '租户 ID';
COMMENT ON COLUMN agent_turn_stats.project_id IS '项目 ID';
COMMENT ON COLUMN agent_turn_stats.agent_id IS '智能体 ID（agt_…）';
COMMENT ON COLUMN agent_turn_stats.hour_start IS '小时的起点（UTC 整点），按轮次的 finished_at 归入';
COMMENT ON COLUMN agent_turn_stats.turns IS '这一小时结束的轮次数：completed、interrupted、failed（排队时被取消的 cancelled 不计）';
COMMENT ON COLUMN agent_turn_stats.failed_turns IS '其中 failed 的轮次数';
COMMENT ON COLUMN agent_turn_stats.updated_at IS '最近一次累加的时间';

-- ---------------------------------------------------------------------------------------------
-- 汇总作业的水位
-- ---------------------------------------------------------------------------------------------
CREATE TABLE stats_watermarks (
    name       text        PRIMARY KEY,
    watermark  timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

COMMENT ON TABLE stats_watermarks IS '后台汇总作业的水位：结束时间不晚于水位的轮次已经计入汇总；多个节点经行锁串行';
COMMENT ON COLUMN stats_watermarks.name IS '汇总的名字，例如 agent_turn_stats';
COMMENT ON COLUMN stats_watermarks.watermark IS '已汇总到的时间（含）';
COMMENT ON COLUMN stats_watermarks.updated_at IS '最近一次推进水位的时间';
