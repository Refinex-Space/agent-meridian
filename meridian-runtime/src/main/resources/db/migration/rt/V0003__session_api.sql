-- 会话 API（T07）：会话的删除时间，以及按终端用户列出会话的索引。

-- 删除即终止，会话数据在 24 小时内由清理作业删除（T17a）；在那之前按 ID 仍可查询，列表中不再出现
ALTER TABLE sessions ADD COLUMN deleted_at timestamptz;

COMMENT ON COLUMN sessions.deleted_at IS '删除时间；为空表示未删除。终止（terminated_at 不为空）但未删除即已归档';

-- GET /v1/sessions?end_user_id= 按项目与终端用户游标分页
CREATE INDEX sessions_end_user_idx ON sessions (tenant_id, project_id, end_user_id, id) WHERE end_user_id IS NOT NULL;
