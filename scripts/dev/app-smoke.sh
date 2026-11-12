#!/usr/bin/env bash
# Compose app profile 的端到端检查（T14a-A08，同时实测 A07 与 A03 的 runtime 部分；T16 的零预算、账本与审计）：
#   1. dev-api-key.sh 经管理 API 新建租户（并发配额 4，不设预算）、项目与 API Key；
#   2. 新租户默认零预算：第一轮以 budget_exceeded 结束；经管理 API 设置四级预算后，脚本化模型回答、会话回到 idle；
#      control-plane 的 GET /v1/usage 看到这一轮的模型用量，审计查询看到它的 turn.finished（T16）；
#   3. rt.tenant_concurrency 的 max_running 等于配额（不手工插入任何数据），改配额后随之更新；
#   4. 吊销 Key 后 1 秒内 runtime 拒绝它；
#   5. 控制台用户（Keycloak 的 dev-admin，新租户的所有者）带 Meridian-Project 调用 runtime 公开 API，内部调用全程是服务 JWT（T14b）。
# 前提：scripts/dev/build-images.sh；docker compose -f deploy/compose/compose.yaml --env-file <.env 或 .env.example> --profile app up -d
# （全部服务健康，app profile 含 Keycloak）。连接信息读 deploy/compose/.env，不存在时读 .env.example；Key 明文只写进临时文件，结束时删除。
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

output="$(scripts/dev/dev-api-key.sh --control-plane="${control_plane}" --quota=4 --output="${key_file}" --no-budgets)"
echo "${output}"
tenant_id="$(sed -n 's/^tenant_id=//p' <<<"${output}")"
project_id="$(sed -n 's/^project_id=//p' <<<"${output}")"
key="$(cat "${key_file}")"
token="$(scripts/dev/console-token.sh dev-admin)"
auth=(-H "Authorization: Bearer ${key}" -H 'Content-Type: application/json')

session_id="$(curl -sf --noproxy '*' "${auth[@]}" -X POST "${runtime}/v1/sessions" \
    -d '{"agent":"agt_dev_scripted","environment":"env_dev_none"}' | json_field "['id']")"
echo "session_id=${session_id}"
send_message() {
    curl -sf --noproxy '*' "${auth[@]}" -X POST "${runtime}/v1/sessions/${session_id}/events" \
        -d '{"events":[{"type":"user.message","content":[{"type":"text","text":"#script:chat 你好"}]}]}' >/dev/null
}
event_types() {
    curl -sf --noproxy '*' "${auth[@]}" "${runtime}/v1/sessions/${session_id}/events?limit=100" \
        | python3 -I -c "import json,sys; print(' '.join(e['type'] for e in json.load(sys.stdin)['data']))"
}

# 新租户默认零预算（ADR-0048）：第一轮以 budget_exceeded 结束
send_message
stopped=""
for _ in $(seq 1 60); do
    stopped="$(curl -sf --noproxy '*' "${auth[@]}" "${runtime}/v1/sessions/${session_id}/events?limit=100" \
        | python3 -I -c "import json,sys; print(any(e['type']=='session.status_idle' and e['data'].get('stop_reason')=='budget_exceeded' for e in json.load(sys.stdin)['data']))")"
    [[ "${stopped}" == "True" ]] && break
    sleep 1
done
[[ "${stopped}" == "True" ]] || { echo "FAIL：没有设置预算的新租户，轮次没有以 budget_exceeded 结束" >&2; exit 1; }
[[ "$(event_types)" != *"agent.message"* ]] || { echo "FAIL：零预算时模型仍然回答了" >&2; exit 1; }
echo "PASS 新租户在设置预算之前，模型调用以 budget_exceeded 被拒绝"
for level in tenant project api_key session; do
    curl -sf --noproxy '*' -X POST "${control_plane}/admin/v1/tenants/${tenant_id}/budgets" \
        -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' \
        -d "{\"level\":\"${level}\",\"hard_limit_tokens\":null}" >/dev/null
done
echo "PASS 经管理 API 把四级预算设为不限"
send_message

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

usage=""
for _ in $(seq 1 30); do
    usage="$(curl -sf --noproxy '*' "${auth[@]}" "${control_plane}/v1/usage?group_by=model" | python3 -I -c "
import json, sys
rows = [r for r in json.load(sys.stdin)['rows'] if r['kind'] == 'model_tokens']
print(sum(r['input_tokens'] + r['output_tokens'] for r in rows) if rows else '')")"
    [[ -n "${usage}" && "${usage}" != "0" ]] && break
    sleep 1
done
[[ -n "${usage}" && "${usage}" != "0" ]] || { echo "FAIL：GET /v1/usage 没有这一轮的模型用量" >&2; exit 1; }
echo "PASS control-plane 的 GET /v1/usage 看到这一轮的模型用量（${usage} token）"
audit=""
for _ in $(seq 1 30); do
    audit="$(curl -sf --noproxy '*' -H "Authorization: Bearer ${token}" \
        "${control_plane}/admin/v1/tenants/${tenant_id}/audit-logs?session_id=${session_id}&action=turn.finished" \
        | python3 -I -c "import json,sys; print(len(json.load(sys.stdin)['data']))")"
    [[ "${audit}" -ge 2 ]] && break
    sleep 1
done
[[ "${audit}" -ge 2 ]] || { echo "FAIL：审计查询没有这个会话的两条 turn.finished（${audit}）" >&2; exit 1; }
echo "PASS 审计查询按会话检索到 runtime 的 turn.finished（${audit} 条）"

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

console=(-H "Authorization: Bearer ${token}" -H "Meridian-Project: ${project_id}")
code="$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "${console[@]}" "${runtime}/v1/sessions")"
[[ "${code}" == "200" ]] || { echo "FAIL：控制台用户带 Meridian-Project 列会话返回 ${code}" >&2; exit 1; }
viewer="$(scripts/dev/console-token.sh dev-viewer)"
code="$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -H "Authorization: Bearer ${viewer}" \
    -H "Meridian-Project: ${project_id}" "${runtime}/v1/sessions")"
[[ "${code}" == "403" ]] || { echo "FAIL：不在项目中的用户返回 ${code}，期望 403" >&2; exit 1; }
echo "PASS 控制台用户按项目角色调用公开 API（所有者 200，非成员 403）"
