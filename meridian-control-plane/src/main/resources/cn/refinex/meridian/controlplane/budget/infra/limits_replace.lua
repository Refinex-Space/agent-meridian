-- 整体替换一个租户的预算限额哈希（ADR-0048 第 1 条）：先删除再写入，读到的总是完整的一份。
-- KEYS[1]  限额哈希 budget:{租户}:limits
-- ARGV     字段与取值交替出现；为空时只删除（没有任何预算：所有级别按 0 处理）
redis.call('DEL', KEYS[1])
if #ARGV > 0 then
  redis.call('HSET', KEYS[1], unpack(ARGV))
end
return #ARGV / 2
