-- 会话记下创建它的 API Key（ADR-0048 第 2 条）：会话中的模型调用、沙箱与搜索的用量计入这个 Key 的预算与报表；控制台用户创建的
-- 会话为空。
ALTER TABLE sessions ADD COLUMN api_key_id text;

COMMENT ON COLUMN sessions.api_key_id IS '创建会话的 API Key；控制台用户创建时为空。轮次的预算与用量计入它（ADR-0048）';
