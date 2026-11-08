-- 结算一次预扣：各级已用计数加上"实际用量减预扣量"（退回多扣的部分），删除预扣记录。释放预扣就是实际用量为 0 的结算。
-- 预扣记录不存在（已经结算过，或超过过期时间）时什么都不做，重复结算不会重复退回。
-- KEYS[1..n]  各级预算的已用计数，n = #KEYS - 1
-- KEYS[n+1]   预扣记录
-- ARGV[1]     实际用量（token）
-- 返回        各级的调整量；预扣记录不存在时为 nil
local n = #KEYS - 1
local reserved = redis.call('GET', KEYS[n + 1])
if not reserved then
  return false
end
local delta = tonumber(ARGV[1]) - tonumber(reserved)
for i = 1, n do
  redis.call('INCRBY', KEYS[i], delta)
end
redis.call('DEL', KEYS[n + 1])
return delta
