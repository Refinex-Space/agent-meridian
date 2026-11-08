-- 结算后把 TPM 桶中多扣的 token 退回（预扣按"估算输入加最大输出"扣，实际用量通常更少），不超过桶容量。
-- KEYS[1]  令牌桶哈希，字段同 rate_limit_acquire.lua
-- ARGV[1]  当前时间（毫秒）
-- ARGV[2]  TPM，0 表示不限（直接返回）
-- ARGV[3]  退回的 token 数
-- 返回     退回后的余量（取整）；不限或桶不存在时为 -1
local tpm = tonumber(ARGV[2])
if tpm <= 0 then
  return -1
end
local state = redis.call('HMGET', KEYS[1], 't', 'tts')
if state[1] == false then
  return -1
end
local now = tonumber(ARGV[1])
local elapsed = now - tonumber(state[2])
if elapsed < 0 then
  elapsed = 0
end
local t = math.min(tpm, tonumber(state[1]) + elapsed * tpm / 60000 + tonumber(ARGV[3]))
redis.call('HSET', KEYS[1], 't', tostring(t), 'tts', tostring(now))
return math.floor(t)
