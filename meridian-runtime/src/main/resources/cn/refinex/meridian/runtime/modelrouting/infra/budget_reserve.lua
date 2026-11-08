-- 四级预算的预扣（架构设计「计量与预算」）：任一级的已用量加本次预扣超过硬阈值就整体失败，否则各级同时加上预扣量并记下预扣。
-- 已用计数含尚未结算的预扣，结算时按实际用量修正。
-- KEYS[1..n]  各级预算的已用计数（字符串整数），n = #KEYS - 1
-- KEYS[n+1]   本次预扣的记录（值为预扣量）
-- ARGV[1]     预扣量（token）
-- ARGV[2]     预扣记录的过期秒数
-- ARGV[3]     1 表示占用，0 表示只检查
-- ARGV[4..n+3] 各级的硬阈值，负数表示不限
-- 返回        0 表示成功；否则为第一个不足的级别在 KEYS 中的序号（从 1 起）
local n = #KEYS - 1
local amount = tonumber(ARGV[1])
for i = 1, n do
  local limit = tonumber(ARGV[i + 3])
  if limit >= 0 then
    local used = tonumber(redis.call('GET', KEYS[i]) or '0')
    if used + amount > limit then
      return i
    end
  end
end
if ARGV[3] ~= '1' then
  return 0
end
for i = 1, n do
  redis.call('INCRBY', KEYS[i], amount)
end
redis.call('SET', KEYS[n + 1], amount, 'EX', tonumber(ARGV[2]))
return 0
