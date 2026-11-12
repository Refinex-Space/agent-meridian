-- 对账修正一级预算的已用计数（ADR-0048 第 5 条）：加上修正量，并刷新过期时间（键不存在时由修正量创建）。
-- 用增量而不是覆盖：读取与修正之间并发的预扣、结算不会丢失。
-- KEYS[1]  已用计数 budget:{租户}:used:{级别}:{范围}:{周期}
-- ARGV[1]  修正量（可为负）
-- ARGV[2]  过期秒数
-- 返回     修正后的计数
local value = redis.call('INCRBY', KEYS[1], tonumber(ARGV[1]))
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
return value
