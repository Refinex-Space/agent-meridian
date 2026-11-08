-- "供应商账号加模型"的 RPM 与 TPM 令牌桶（架构设计「模型调用」第 2 步），两个桶在同一个哈希里原子地检查与扣减。
-- 桶容量等于每分钟上限，按每毫秒 上限/60000 匀速补充；新桶是满的。
-- KEYS[1]  令牌桶哈希：r/rts 为请求桶的余量与上次补充时间，t/tts 为 token 桶的余量与上次补充时间
-- ARGV[1]  当前时间（毫秒，应用注入的 Clock）
-- ARGV[2]  RPM，0 表示不限
-- ARGV[3]  TPM，0 表示不限
-- ARGV[4]  本次的 token 估算；超过 TPM 时按 TPM 计，满桶即可通过
-- ARGV[5]  1 表示拿到配额后扣减，0 表示只检查
-- 返回     0 表示可以发出请求；否则为还需等待的毫秒数（此时不扣减）
local function refill(capacity, tokens, ts, now)
  if tokens == nil then
    return capacity
  end
  local elapsed = now - ts
  if elapsed < 0 then
    elapsed = 0
  end
  return math.min(capacity, tokens + elapsed * capacity / 60000)
end

-- 补充量是浮点数，取整前减去一个极小量，避免 9999.9999 这类误差被向上取成多等 1 毫秒
local function wait_millis(shortfall, capacity)
  return math.ceil(shortfall * 60000 / capacity - 1e-6)
end

local now = tonumber(ARGV[1])
local rpm = tonumber(ARGV[2])
local tpm = tonumber(ARGV[3])
local need = tonumber(ARGV[4])
local take = ARGV[5] == '1'
local state = redis.call('HMGET', KEYS[1], 'r', 'rts', 't', 'tts')
local wait = 0
local r
local t

if rpm > 0 then
  r = refill(rpm, tonumber(state[1]), tonumber(state[2]), now)
  if r < 1 then
    wait = math.max(wait, wait_millis(1 - r, rpm))
  end
end
if tpm > 0 then
  if need > tpm then
    need = tpm
  end
  t = refill(tpm, tonumber(state[3]), tonumber(state[4]), now)
  if t < need then
    wait = math.max(wait, wait_millis(need - t, tpm))
  end
end

if wait > 0 or not take then
  return wait
end
if rpm > 0 then
  redis.call('HSET', KEYS[1], 'r', tostring(r - 1), 'rts', tostring(now))
end
if tpm > 0 then
  redis.call('HSET', KEYS[1], 't', tostring(t - need), 'tts', tostring(now))
end
-- 两分钟内没有请求时桶早已补满，删掉哈希与新建一个满桶等价
redis.call('PEXPIRE', KEYS[1], 120000)
return 0
