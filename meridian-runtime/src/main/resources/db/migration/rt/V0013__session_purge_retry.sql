-- M1 后端评审（R2）：已删除会话的清理逐个推迟重试；删除从未使用的会话预算列。

-- 清理等待 Webhook 投递或失败时只推迟这一个会话（审查 C-02）：此前清理按删除时间取最早的一批，等待中或总是失败的会话一直排在
-- 队头，后面删除的会话都清理不到
ALTER TABLE sessions ADD COLUMN purge_after timestamptz;

COMMENT ON COLUMN sessions.purge_after IS '已删除会话下一次尝试清理的最早时间；为空表示随时可以清理。清理等待或失败时推迟';

-- 会话级预算在 control-plane 的 budgets（ADR-0048），这一列从 V0001 起没有被读写过（登记事项 D67）
ALTER TABLE sessions DROP COLUMN budget_limit;
