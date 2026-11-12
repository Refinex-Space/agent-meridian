#!/usr/bin/env bash
# 凭据库与模型目录的端到端检查（T15a-A03，ADR-0045）：经 control-plane 的管理 API 建凭据、供应商账号、价格表与模型，再用 API Key 调
# runtime 的 /v1/chat/completions，模型调用的端点与凭据都由 runtime 经内部接口从 control-plane 解析：
#   1. 新建租户、项目与带 models:invoke 的 API Key；
#   2. 凭据 → 供应商账号（脚本化模型服务）→ 价格表 → 模型，runtime 调用成功，凭据的最后使用时间被记下，任何管理响应都不含明文；
#   3. 轮换凭据后再调用一次（config.invalidate 立即生效）；
#   4. 给出 --dashscope 且设置了 DASHSCOPE_API_KEY 时，同样经管理 API 接入 DashScope 兼容端点调用一次真实模型（约 1 次调用）。
# 前提：scripts/dev/up.sh；Compose 的 auth profile（Keycloak）；scripts/dev/run-control-plane.sh；scripts/dev/scripted-model.sh；
# runtime 以 control-plane 目录启动：MERIDIAN_RUNTIME_CATALOG_SOURCE=control-plane scripts/dev/run-runtime.sh --server.port=18080。
# 用法：scripts/dev/catalog-smoke.sh [--dashscope] [--control-plane=http://localhost:18081] [--runtime=http://localhost:18080]
# 只打印 ID 与状态，不打印 Key、令牌与凭据明文（凭据值经临时文件交给 curl，结束时删除）。
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

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT
token="$(scripts/dev/console-token.sh dev-admin)"
dev_admin_sub="00000000-0000-4000-8000-000000000001"

# 调管理 API，响应体写进 ${work}/response；非 2xx 时打印状态码与 problem+json（其中不含凭据）并退出
admin() {
    local method="$1" path="$2" body_file="${3:-}" status data=()
    [[ -n "${body_file}" ]] && data=(-H 'Content-Type: application/json' --data-binary "@${body_file}")
    status="$(curl -s --noproxy '*' -o "${work}/response" -w '%{http_code}' -X "${method}" "${control_plane}${path}" \
        -H "Authorization: Bearer ${token}" ${data[@]+"${data[@]}"})"
    if [[ "${status}" != 2* ]]; then
        echo "FAIL ${method} ${path}：${status} $(cat "${work}/response")" >&2
        exit 1
    fi
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

chat() {
    local model="$1"
    printf '{"model":"%s","max_tokens":64,"messages":[{"role":"user","content":"用一句话介绍你自己。"}]}' "${model}" >"${work}/chat"
    curl -s --noproxy '*' -o "${work}/chat-response" -w '%{http_code}' -X POST "${runtime}/v1/chat/completions" \
        -H "Authorization: Bearer $(cat "${work}/key")" -H 'Content-Type: application/json' --data-binary "@${work}/chat"
}

assert_chat() {
    local label="$1" model="$2" status
    status="$(chat "${model}")"
    if [[ "${status}" != 200 ]]; then
        echo "FAIL ${label}：runtime 返回 ${status} $(cat "${work}/chat-response")" >&2
        exit 1
    fi
    python3 -I - "${work}/chat-response" "${label}" <<'PY'
import json, sys
response = json.load(open(sys.argv[1]))
content = response["choices"][0]["message"]["content"]
assert content, "回答为空"
usage = response.get("usage") or {}
print(f"PASS {sys.argv[2]}：模型回答 {len(content)} 个字符，usage={usage.get('prompt_tokens')}/{usage.get('completion_tokens')}")
PY
}

# 1. 租户、项目与 API Key（请求体用单引号的 printf 格式组装：macOS 自带的 bash 3.2 解析 "$(… "{\"…\"}")" 会丢字符）
admin POST /admin/v1/tenants "$(body "$(printf '{"name":"目录检查","owner":{"subject":"%s"}}' "${dev_admin_sub}")")"
tenant_id="$(field "['id']")"
base="/admin/v1/tenants/${tenant_id}"
admin POST "${base}/projects" "$(body '{"name":"目录检查项目"}')"
project_id="$(field "['id']")"
admin POST "${base}/projects/${project_id}/api-keys" "$(body '{"name":"目录检查","scopes":["models:invoke"]}')"
(umask 077 && field "['key']" >"${work}/key")
echo "PASS 新建租户 ${tenant_id}、项目 ${project_id} 与 API Key"

# 2. 凭据、供应商账号、价格表、模型（脚本化模型服务）
admin POST "${base}/secrets" "$(secret_body "脚本化模型" random)"
secret_id="$(field "['id']")"
admin POST "${base}/provider-accounts" "$(body "$(printf \
    '{"name":"脚本化模型服务","provider":"openai_compatible","base_url":"%s","secret_id":"%s"}' "${scripted}" "${secret_id}")")"
account_id="$(field "['id']")"
admin POST "${base}/price-books" "$(body \
    '{"name":"脚本化","currency":"CNY","prices":{"input":"0.8","output":"2","cache_read":"0.16","cache_write":"0.8","reasoning":"8"}}')"
price_book_id="$(field "['id']")"
model_format='{"provider_account_id":"%s","name":"%s","kind":"chat","upstream_model":"%s","context_window":131072,'
model_format+='"max_output_tokens":1024%s}'
admin POST "${base}/models" "$(body "$(printf "${model_format}" "${account_id}" "脚本化对话" scripted \
    ",\"price_book_id\":\"${price_book_id}\"")")"
model_id="$(field "['id']")"
echo "PASS 管理 API 建好凭据 ${secret_id}、供应商账号 ${account_id}、价格表 ${price_book_id}、模型 ${model_id}"
assert_chat "runtime 经 control-plane 解析模型与凭据（脚本化）" "${model_id}"
admin GET "${base}/secrets/${secret_id}"
[[ "$(field "['last_used_at']")" != "None" ]] || { echo "FAIL 凭据没有记下最后使用时间" >&2; exit 1; }
echo "PASS 凭据的最后使用时间：$(field "['last_used_at']")；引用方：$(field "['references'][0]['type']")"

# 3. 轮换凭据后再调用
python3 -I -c "import json,secrets; print(json.dumps({'value': 'rotated-' + secrets.token_hex(16)}))" >"${work}/rotate"
admin POST "${base}/secrets/${secret_id}:rotate" "${work}/rotate"
echo "PASS 轮换凭据，版本 $(field "['version']")"
assert_chat "轮换之后的调用" "${model_id}"

# 4. 真实模型（DashScope 兼容端点）
if [[ "${dashscope}" == true ]]; then
    if [[ -z "${DASHSCOPE_API_KEY:-}" ]]; then
        echo "SKIP 没有设置 DASHSCOPE_API_KEY，跳过真实模型"
    else
        admin POST "${base}/secrets" "$(secret_body "DashScope" DASHSCOPE_API_KEY)"
        ds_secret="$(field "['id']")"
        admin POST "${base}/provider-accounts" "$(body "$(printf \
            '{"name":"DashScope","provider":"openai_compatible","base_url":"%s","secret_id":"%s"}' \
            "https://dashscope.aliyuncs.com/compatible-mode/v1" "${ds_secret}")")"
        ds_account="$(field "['id']")"
        admin POST "${base}/models" "$(body "$(printf "${model_format}" "${ds_account}" "通义千问" \
            "${MERIDIAN_LIVE_CHAT_MODEL:-qwen-plus}" "")")"
        assert_chat "runtime 经 control-plane 解析 DashScope 的端点与凭据（真实模型）" "$(field "['id']")"
    fi
fi
echo "全部通过"
