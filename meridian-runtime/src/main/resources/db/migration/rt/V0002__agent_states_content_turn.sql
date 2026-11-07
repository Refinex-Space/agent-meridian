-- 记录最近一次状态内容写入来自哪一轮：lease_epoch 每轮从 0 开始，只凭 content_epoch 分不清内容是本轮还是上一轮写的，
-- 崩溃恢复据 (content_turn_id, content_epoch) 判断恢复方式（ADR-0028）。

ALTER TABLE agent_states ADD COLUMN content_turn_id text;

COMMENT ON COLUMN agent_states.content_epoch IS '最近一次内容写入（权限上下文的安装写入除外）时的租约 epoch，与 content_turn_id 一起标识写入它的那次执行；为空表示还没有内容写入';
COMMENT ON COLUMN agent_states.content_turn_id IS '最近一次内容写入所属的轮次（子线程写入时为父轮次）；不建外键，轮次随会话删除。为空表示还没有内容写入，或写入早于本列';
