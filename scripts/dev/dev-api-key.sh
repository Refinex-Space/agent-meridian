#!/usr/bin/env bash
# 经管理 API 建开发租户、项目并签发 API Key（T14a）：以开发期控制台令牌（dev-admin，平台管理员）调用 control-plane，
# Key 明文默认写入 deploy/compose/.env 的 MERIDIAN_DEV_API_KEY（.env 不存在时从 .env.example 复制；.env 已被 Git 忽略），
# 给出 --output 时只把明文写进该文件。只打印 ID 与显示前缀，不打印明文。Key 带 sessions:read、sessions:write、models:invoke，
# 限流取上限（压测用）。控制台令牌读 .env，不存在时读 .env.example（与 up.sh 一致）。
# 用法：scripts/dev/dev-api-key.sh [--control-plane=http://localhost:18081] [--quota=20] [--output=<文件>]
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${repo_root}"

control_plane="http://localhost:18081"
quota=""
output=""
for arg in "$@"; do
    case "${arg}" in
        --control-plane=*) control_plane="${arg#--control-plane=}" ;;
        --quota=*) quota="${arg#--quota=}" ;;
        --output=*) output="${arg#--output=}" ;;
        *) echo "未知参数：${arg}" >&2; exit 2 ;;
    esac
done

env_file="deploy/compose/.env"
source_file="${env_file}"
[[ -f "${source_file}" ]] || source_file="deploy/compose/.env.example"
token="$(grep -E '^MERIDIAN_DEV_CONSOLE_TOKEN=' "${source_file}" | cut -d= -f2-)"

# 发请求并取响应体中的一个字段；失败时打印状态码与 problem+json（其中不含凭据）
call() {
    local method="$1" path="$2" body="$3" field="$4" response status
    response="$(curl -s --noproxy '*' -w '\n%{http_code}' -X "${method}" "${control_plane}${path}" \
        -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' -d "${body}")"
    status="${response##*$'\n'}"
    response="${response%$'\n'*}"
    if [[ "${status}" != 2* ]]; then
        echo "${method} ${path} 失败：${status} ${response}" >&2
        exit 1
    fi
    printf '%s' "${response}" | python3 -I -c "import json,sys; print(json.load(sys.stdin)['${field}'])"
}

quota_field=""
[[ -n "${quota}" ]] && quota_field=",\"max_concurrent_turns\":${quota}"
tenant_id="$(call POST /admin/v1/tenants "{\"name\":\"开发租户\",\"owner\":{\"subject\":\"dev-admin\"}${quota_field}}" id)"
project_id="$(call POST "/admin/v1/tenants/${tenant_id}/projects" '{"name":"开发项目"}' id)"
key_body='{"name":"开发 Key","scopes":["sessions:read","sessions:write","models:invoke"],"rate_limit_per_minute":100000}'
key_json_path="/admin/v1/tenants/${tenant_id}/projects/${project_id}/api-keys"
plaintext="$(call POST "${key_json_path}" "${key_body}" key)"

if [[ -n "${output}" ]]; then
    (umask 077 && printf '%s' "${plaintext}" >"${output}")
    destination="${output}"
else
    [[ -f "${env_file}" ]] || cp deploy/compose/.env.example "${env_file}"
    python3 -I - "${env_file}" "${plaintext}" <<'PY'
import sys
path, key = sys.argv[1], sys.argv[2]
lines = [line for line in open(path, encoding="utf-8").read().splitlines() if not line.startswith("MERIDIAN_DEV_API_KEY=")]
lines.append("MERIDIAN_DEV_API_KEY=" + key)
open(path, "w", encoding="utf-8").write("\n".join(lines) + "\n")
PY
    destination="${env_file} 的 MERIDIAN_DEV_API_KEY"
fi
echo "tenant_id=${tenant_id}"
echo "project_id=${project_id}"
echo "api_key=${plaintext:0:16}…（明文已写入 ${destination}）"
