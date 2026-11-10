#!/usr/bin/env bash
# 取开发 realm 中一个控制台用户的 OIDC access token（T14b）：经 Compose auth profile 的 Keycloak，以开发脚本用的客户端
# meridian-dev-cli 口令直接换取。令牌输出到标准输出，供其他脚本放进 Authorization 头，不写文件、不记日志。
# 用法：scripts/dev/console-token.sh [用户名，默认 dev-admin] [--keycloak=http://localhost:18082]
# 开发用户（deploy/keycloak/meridian-dev-realm.json）：dev-admin（平台管理员组）、dev-developer、dev-viewer，口令 meridian-dev。
set -euo pipefail

username="dev-admin"
keycloak="http://localhost:${MERIDIAN_KEYCLOAK_PORT:-18082}"
for arg in "$@"; do
    case "${arg}" in
        --keycloak=*) keycloak="${arg#--keycloak=}" ;;
        -*) echo "未知参数：${arg}" >&2; exit 2 ;;
        *) username="${arg}" ;;
    esac
done

response="$(curl -s --noproxy '*' -w '\n%{http_code}' \
    -X POST "${keycloak}/realms/meridian-dev/protocol/openid-connect/token" \
    --data-urlencode grant_type=password --data-urlencode client_id=meridian-dev-cli \
    --data-urlencode "username=${username}" --data-urlencode password=meridian-dev --data-urlencode scope=openid)"
status="${response##*$'\n'}"
if [[ "${status}" != 200 ]]; then
    echo "从 ${keycloak} 取 ${username} 的令牌失败：HTTP ${status}（先启动 Compose 的 auth profile）" >&2
    exit 1
fi
printf '%s' "${response%$'\n'*}" | python3 -I -c "import json,sys; print(json.load(sys.stdin)['access_token'])"
