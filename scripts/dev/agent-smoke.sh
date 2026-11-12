#!/usr/bin/env bash
# 智能体配置分发的端到端检查（T15b-A01，ADR-0047）：只经 control-plane 的管理 API 建供应商账号、模型、凭据、智能体、发布版本、环境，
# 业务方用 API Key 在 runtime 创建会话并完成一轮对话；runtime 的智能体、环境、模型与凭据都经内部接口从 control-plane 读取：
#   1. 新建租户、项目与带 sessions:read、sessions:write 的 API Key；
#   2. 凭据 → 供应商账号 → 模型（默认脚本化模型服务；--dashscope 时 DashScope 兼容端点的 qwen-plus，需要 DASHSCOPE_API_KEY）；
#   3. 环境（none 档）、智能体、草稿版本、发布；control-plane 的 GET /v1/agents 用同一个 API Key 能看到它；
#   4. runtime 用智能体 ID 创建会话、发送一条消息，等到 agent.message 与 session.status_idle，会话绑定的是刚发布的版本。
# 前提：scripts/dev/up.sh；Compose 的 auth profile（Keycloak）；scripts/dev/run-control-plane.sh；不带 --dashscope 时
# scripts/dev/scripted-model.sh；runtime 两个来源都改为 control-plane、开发配置目录指向不存在的路径（全程不读 dev-config/）：
#   MERIDIAN_RUNTIME_AGENT_CONFIG_SOURCE=control-plane MERIDIAN_RUNTIME_CATALOG_SOURCE=control-plane \
#     scripts/dev/run-runtime.sh --server.port=18080 --meridian.runtime.dev-agent-config.directory=/nonexistent \
#     --meridian.runtime.model-routing.providers-directory=/nonexistent
# 用法：scripts/dev/agent-smoke.sh [--dashscope] [--control-plane=http://localhost:18081] [--runtime=http://localhost:18080]
# 只打印 ID 与状态，不打印 Key、令牌与凭据明文（凭据值经权限 600 的临时文件交给 curl，结束时删除）。
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${repo_root}"

control_plane="http://localhost:18081"
runtime="http://localhost:18080"
scripted="http://localhost:18090/v1"
dashscope=false
for arg in "$@"; do
    case "${arg}" in
        --dashscope) dashscope=true ;;
        --control-plane=*) control_plane="${arg#--control-plane=}" ;;
        --runtime=*) runtime="${arg#--runtime=}" ;;
        *) echo "未知参数：${arg}" >&2; exit 2 ;;
    esac
done
if [[ "${dashscope}" == true && -z "${DASHSCOPE_API_KEY:-}" ]]; then
    echo "FAIL --dashscope 需要环境变量 DASHSCOPE_API_KEY" >&2
    exit 1
fi

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
token="$(scripts/dev/console-token.sh dev-admin)"
dev_admin_sub="00000000-0000-4000-8000-000000000001"

# 调接口，响应体写进 ${work}/response；非 2xx 时打印状态码与 problem+json（其中不含凭据）并退出
call() {
    local method="$1" url="$2" auth="$3" body_file="${4:-}" status data=()
    [[ -n "${body_file}" ]] && data=(-H 'Content-Type: application/json' --data-binary "@${body_file}")
    status="$(curl -s --noproxy '*' -o "${work}/response" -w '%{http_code}' -X "${method}" "${url}" \
        -H "Authorization: Bearer ${auth}" ${data[@]+"${data[@]}"})"
    if [[ "${status}" != 2* ]]; then
        echo "FAIL ${method} ${url#http://*/}：${status} $(cat "${work}/response")" >&2
        exit 1
    fi
}

admin() {
    call "$1" "${control_plane}$2" "${token}" "${3:-}"
}

field() {
    python3 -I -c "import json,sys; d=json.load(open(sys.argv[1])); print(d$1)" "${work}/response"
}

body() {
    printf '%s' "$1" >"${work}/body"
    echo "${work}/body"
}

# 凭据的请求体：值来自环境变量或随机生成，只写进权限 600 的临时文件
secret_body() {
    local name="$1" source="$2"
    (umask 077 && python3 -I - "${name}" "${source}" >"${work}/secret" <<'PY'
import json, os, secrets, sys
name, source = sys.argv[1], sys.argv[2]
value = os.environ[source] if source != "random" else "scripted-" + secrets.token_hex(16)
print(json.dumps({"name": name, "value": value}))
PY
    )
    echo "${work}/secret"
}

# 1. 租户、项目与 API Key（请求体用单引号的 printf 格式组装：macOS 自带的 bash 3.2 解析 "$(… "{\"…\"}")" 会丢字符）
admin POST /admin/v1/tenants "$(body "$(printf '{"name":"智能体检查","owner":{"subject":"%s"}}' "${dev_admin_sub}")")"
tenant_id="$(field "['id']")"
base="/admin/v1/tenants/${tenant_id}"
admin POST "${base}/projects" "$(body '{"name":"智能体检查项目"}')"
project_id="$(field "['id']")"
project="${base}/projects/${project_id}"
admin POST "${project}/api-keys" "$(body '{"name":"智能体检查","scopes":["sessions:read","sessions:write"]}')"
(umask 077 && field "['key']" >"${work}/key")
echo "PASS 新建租户 ${tenant_id}、项目 ${project_id} 与 API Key"
# 新租户默认零预算（ADR-0048）：四级都显式设为不限，模型调用才能进行
for level in tenant project api_key session; do
    admin POST "${base}/budgets" "$(body "$(printf '{"level":"%s","hard_limit_tokens":null}' "${level}")")"
done
echo "PASS 四级预算设为不限"

# 2. 凭据、供应商账号、模型
if [[ "${dashscope}" == true ]]; then
    admin POST "${base}/secrets" "$(secret_body "DashScope" DASHSCOPE_API_KEY)"
    endpoint="https://dashscope.aliyuncs.com/compatible-mode/v1"
    upstream="${MERIDIAN_LIVE_CHAT_MODEL:-qwen-plus}"
    message="用一句话介绍你自己。"
else
    admin POST "${base}/secrets" "$(secret_body "脚本化模型" random)"
    endpoint="${scripted}"
    upstream="scripted"
    message="#script:chat 用一句话介绍你自己。"
fi
secret_id="$(field "['id']")"
admin POST "${base}/provider-accounts" "$(body "$(printf \
    '{"name":"供应商","provider":"openai_compatible","base_url":"%s","secret_id":"%s"}' "${endpoint}" "${secret_id}")")"
account_id="$(field "['id']")"
admin POST "${base}/models" "$(body "$(printf \
    '{"provider_account_id":"%s","name":"对话模型","kind":"chat","upstream_model":"%s","context_window":131072,"max_output_tokens":512}' \
    "${account_id}" "${upstream}")")"
model_id="$(field "['id']")"
echo "PASS 管理 API 建好凭据 ${secret_id}、供应商账号 ${account_id}、模型 ${model_id}（${upstream}）"

# 3. 环境、智能体、版本与发布
admin POST "${project}/environments" "$(body '{"name":"对话","tier":"none"}')"
environment_id="$(field "['id']")"
admin POST "${project}/agents" "$(body '{"name":"smoke-assistant","description":"端到端检查用的助手"}')"
agent_id="$(field "['id']")"
spec_format='{"spec":{"model":{"model_id":"%s","max_output_tokens":256},"system_prompt":"你是企业内的通用助手，回答简洁。",'
spec_format+='"tools":[],"mcp_servers":[],"skills":[],"subagents":[],"permissions":{"mode":"default","rules":[]},'
spec_format+='"compaction":{"reserved_tokens":20000},"plan_mode":false,'
spec_format+='"knowledge":{"knowledge_base_ids":[],"prefetch":false,"prefetch_top_k":5},"max_iters":10,"expose_thinking":false}}'
admin POST "${project}/agents/${agent_id}/versions" "$(body "$(printf "${spec_format}" "${model_id}")")"
admin POST "${project}/agents/${agent_id}/versions/1:publish"
version_id="$(field "['id']")"
[[ "$(field "['status']")" == "published" ]] || { echo "FAIL 版本没有发布" >&2; exit 1; }
echo "PASS 环境 ${environment_id}；智能体 ${agent_id} 发布了版本 1（${version_id}）"

call GET "${control_plane}/v1/agents" "$(cat "${work}/key")"
python3 -I - "${work}/response" "${agent_id}" "${version_id}" <<'PY'
import json, sys
page = json.load(open(sys.argv[1]))
agents = {a["id"]: a for a in page["data"]}
assert list(agents) == [sys.argv[2]], f"GET /v1/agents 应只有刚发布的智能体：{list(agents)}"
assert agents[sys.argv[2]]["current_version"]["id"] == sys.argv[3]
print("PASS control-plane 的 GET /v1/agents（API Key）只列出本项目已发布的智能体")
PY

# 4. runtime：用智能体 ID 创建会话、发送消息、等到回答
key="$(cat "${work}/key")"
call POST "${runtime}/v1/sessions" "${key}" "$(body "$(printf '{"agent":"%s","environment":"%s"}' "${agent_id}" "${environment_id}")")"
session_id="$(field "['id']")"
[[ "$(field "['agent_version_id']")" == "${version_id}" ]] || { echo "FAIL 会话没有绑定刚发布的版本" >&2; exit 1; }
python3 -I -c "import json,sys; print(json.dumps({'events':[{'type':'user.message','content':[{'type':'text','text':sys.argv[1]}]}]}))" \
    "${message}" >"${work}/message"
call POST "${runtime}/v1/sessions/${session_id}/events" "${key}" "${work}/message"
for _ in $(seq 1 60); do
    call GET "${runtime}/v1/sessions/${session_id}/events?limit=100" "${key}"
    if python3 -I - "${work}/response" <<'PY'
import json, sys
events = json.load(open(sys.argv[1]))["data"]
types = [e["type"] for e in events]
if "session.error" in types:
    error = next(e for e in events if e["type"] == "session.error")
    print("FAIL 本轮出错：" + json.dumps(error["data"], ensure_ascii=False))
    sys.exit(2)
if "session.status_idle" not in types or "agent.message" not in types:
    sys.exit(1)
text = "".join(b.get("text", "") for e in events if e["type"] == "agent.message" for b in e["data"]["content"])
usage = [e["data"] for e in events if e["type"] == "span.model_request_end"]
tokens = usage[-1].get("usage", {}) if usage else {}
print(f"PASS runtime 会话完成一轮对话：回答 {len(text)} 个字符，usage={tokens.get('input_tokens')}/{tokens.get('output_tokens')}")
PY
    then
        echo "PASS 会话 ${session_id} 绑定版本 ${version_id}、环境 ${environment_id}"
        echo "全部通过"
        exit 0
    elif [[ $? == 2 ]]; then
        exit 1
    fi
    sleep 1
done
echo "FAIL 60 秒内没有等到回答" >&2
exit 1
