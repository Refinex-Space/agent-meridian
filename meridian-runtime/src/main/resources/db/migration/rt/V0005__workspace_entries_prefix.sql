-- 工作区（T10a）：MeridianBaseStore 的 search 按命名空间前缀分页读取（RemoteFilesystem 的 ls、glob、grep 都经它）。主键
-- (namespace, key) 只能做整命名空间的精确查找，前缀查询按会话命名空间 [tenant_id, project_id, end_user_id, session_id] 建索引，
-- 按键排序分页
CREATE INDEX workspace_entries_session_idx ON workspace_entries (tenant_id, project_id, (namespace[1:4]), key);
