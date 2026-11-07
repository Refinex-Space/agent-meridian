#!/usr/bin/env bash
# 停止本地基础设施。默认保留数据卷；加 --volumes 才删除 PostgreSQL、Redis、MinIO 的数据。
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
compose_dir="${repo_root}/deploy/compose"
env_file="${compose_dir}/.env"
if [[ ! -f "${env_file}" ]]; then
    env_file="${compose_dir}/.env.example"
fi

args=(down --remove-orphans)
case "${1:-}" in
    "") ;;
    --volumes) args+=(--volumes) ;;
    *)
        echo "用法：$0 [--volumes]" >&2
        exit 2
        ;;
esac

docker compose --file "${compose_dir}/compose.yaml" --env-file "${env_file}" "${args[@]}"
