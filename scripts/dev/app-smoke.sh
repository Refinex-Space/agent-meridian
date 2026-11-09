#!/usr/bin/env bash
# Compose app profile 的端到端检查（T14a-A08，同时实测 A07 与 A03 的 runtime 部分）：
#   1. dev-api-key.sh 经管理 API 新建租户（并发配额 4）、项目与 API Key；
#   2. 用这个 Key 在 runtime 上创建会话、发送消息，脚本化模型回答后会话回到 idle；
#   3. rt.tenant_concurrency 的 max_running 等于配额（不手工插入任何数据），改配额后随之更新；
#   4. 吊销 Key 后 1 秒内 runtime 拒绝它。
# 前提：scripts/dev/build-images.sh；docker compose -f deploy/compose/compose.yaml --env-file <.env 或 .env.example> --profile app up -d
# （全部服务健康）。连接信息读 deploy/compose/.env，不存在时读 .env.example；Key 明文只写进临时文件，结束时删除。
# 用法：scripts/dev/app-smoke.sh；任一步失败以非 0 退出。只打印 ID 与状态，不打印 Key 明文与令牌。
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${repo_root}"

control_plane="http://localhost:${MERIDIAN_CONTROL_PLANE_PORT:-18081}"
runtime="http://localhost:${MERIDIAN_RUNTIME_PORT:-18080}"
env_file="deploy/compose/.env"
[[ -f "${env_file}" ]] || env_file="deploy/compose/.env.example"
compose=(docker compose -f deploy/compose/compose.yaml --env-file "${env_file}")
key_file="$(mktemp)"
trap 'rm -f "${key_file}"' EXIT

json_field() {
    python3 -I -c "import json,sys; d=json.load(sys.stdin); print(d$1)"
}

sql() {
    "${compose[@]}" exec -T postgres psql -U postgres -d meridian -tAc "$1"
}

output="$(scripts/dev/dev-api-key.sh --control-plane="${control_plane}" --quota=4 --output="${key_file}")"
echo "${output}"
tenant_id="$(sed -n 's/^tenant_id=//p' <<<"${output}")"
project_id="$(sed -n 's/^project_id=//p' <<<"${output}")"
key="$(cat "${key_file}")"
token="$(grep -E '^MERIDIAN_DEV_CONSOLE_TOKEN=' "${env_file}" | cut -d= -f2-)"
auth=(-H "Authorization: Bearer ${key}" -H 'Content-Type: application/json')

session_id="$(curl -sf --noproxy '*' "${auth[@]}" -X POST "${runtime}/v1/sessions" \
    -d '{"agent":"agt_dev_scripted","environment":"env_dev_none"}' | json_field "['id']")"
echo "session_id=${session_id}"
curl -sf --noproxy '*' "${auth[@]}" -X POST "${runtime}/v1/sessions/${session_id}/events" \
    -d '{"events":[{"type":"user.message","content":[{"type":"text","text":"#script:chat 你好"}]}]}' >/dev/null

status=""
for _ in $(seq 1 60); do
    status="$(curl -sf --noproxy '*' "${auth[@]}" "${runtime}/v1/sessions/${session_id}" | json_field "['status']")"
    events="$(curl -sf --noproxy '*' "${auth[@]}" "${runtime}/v1/sessions/${session_id}/events?limit=100" \
        | json_field "['data']" | tr -d "'[]" )"
    if [[ "${status}" == "idle" && "${events}" == *"agent.message"* ]]; then
        break
    fi
    sleep 1
done
types="$(curl -sf --noproxy '*' "${auth[@]}" "${runtime}/v1/sessions/${session_id}/events?limit=100" \
    | python3 -I -c "import json,sys; print(' '.join(e['type'] for e in json.load(sys.stdin)['data']))")"
echo "events: ${types}"
[[ "${status}" == "idle" && "${types}" == *"agent.message"* && "${types}" == *"session.status_idle"* ]] \
    || { echo "FAIL：会话没有完成一轮（status=${status}）" >&2; exit 1; }
echo "PASS 经 API Key 跑通一轮会话"

max_running="$(sql "SELECT max_running FROM rt.tenant_concurrency WHERE tenant_id = '${tenant_id}'")"
[[ "${max_running}" == "4" ]] || { echo "FAIL：max_running=${max_running}，期望 4" >&2; exit 1; }
echo "PASS 新租户的计数行由 runtime 创建，max_running=${max_running}"

curl -sf --noproxy '*' -X PUT "${control_plane}/admin/v1/tenants/${tenant_id}/quotas" \
    -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' \
    -d '{"max_concurrent_turns":11,"event_retention_days":365}' >/dev/null
for _ in $(seq 1 20); do
    max_running="$(sql "SELECT max_running FROM rt.tenant_concurrency WHERE tenant_id = '${tenant_id}'")"
    [[ "${max_running}" == "11" ]] && break
    sleep 0.1
done
[[ "${max_running}" == "11" ]] || { echo "FAIL：改配额后 max_running=${max_running}，期望 11" >&2; exit 1; }
echo "PASS 修改配额后 max_running=${max_running}"

key_id="$(curl -sf --noproxy '*' "${control_plane}/admin/v1/tenants/${tenant_id}/projects/${project_id}/api-keys" \
    -H "Authorization: Bearer ${token}" | json_field "['data'][0]['id']")"
curl -sf --noproxy '*' -X POST "${control_plane}/admin/v1/tenants/${tenant_id}/projects/${project_id}/api-keys/${key_id}:revoke" \
    -H "Authorization: Bearer ${token}" >/dev/null
revoked_at="$(python3 -I -c 'import time; print(time.time())')"
code=""
for _ in $(seq 1 20); do
    code="$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "${auth[@]}" "${runtime}/v1/sessions")"
    [[ "${code}" == "401" ]] && break
    sleep 0.05
done
elapsed="$(python3 -I -c "import time; print(round(time.time() - ${revoked_at}, 3))")"
[[ "${code}" == "401" ]] || { echo "FAIL：吊销后 runtime 仍返回 ${code}" >&2; exit 1; }
echo "PASS 吊销后 ${elapsed} 秒内 runtime 拒绝该 Key（401）"
