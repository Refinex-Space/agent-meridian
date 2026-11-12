-- outbox 的认领与失败计数（ADR-0048 第 8 条，登记事项 D49 C-12）：Relay 在短事务中认领、在事务外发布，发布失败的消息不再阻塞
-- 队头；失败达到上限的消息不再自动投递。与 meridian-persistence 的 outbox.sql 一致。
ALTER TABLE outbox ADD COLUMN claimed_until timestamptz;
ALTER TABLE outbox ADD COLUMN attempts integer NOT NULL DEFAULT 0;

COMMENT ON COLUMN outbox.claimed_until IS 'Relay 的认领截止时间：认领后在事务外发布，过期未标记的由任一实例重新认领（ADR-0048）';
COMMENT ON COLUMN outbox.attempts IS '发布失败的次数；达到上限后不再自动投递，等人工处理';
