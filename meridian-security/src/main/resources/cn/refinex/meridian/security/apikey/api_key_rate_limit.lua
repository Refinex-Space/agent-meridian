-- 按 API Key 的令牌桶（ADR-0042）：容量与每分钟补充量都是 ARGV[2]，每次请求消耗一个令牌。
-- KEYS[1] 令牌桶的键；ARGV[1] 当前时间（毫秒，取应用的 Clock）；ARGV[2] 每分钟上限。
-- 返回 {是否放行 1/0, 剩余整数令牌, 补满所需毫秒, 拒绝时得到一个令牌所需毫秒}。
local now = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local rate = capacity / 60000.0
local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil or ts == nil then
  tokens = capacity
  ts = now
end
-- 节点之间的时钟偏差：时间倒退时不补充，也不把时间戳往回改
if now > ts then
  tokens = math.min(capacity, tokens + (now - ts) * rate)
  ts = now
end
local allowed = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
end
redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(ts))
-- 60 秒补满；空闲 2 分钟后删除，下次按满桶重建，结果相同
redis.call('PEXPIRE', KEYS[1], 120000)
local retry = 0
if allowed == 0 then
  retry = math.ceil((1 - tokens) / rate)
end
return {allowed, math.floor(tokens), math.ceil((capacity - tokens) / rate), retry}
