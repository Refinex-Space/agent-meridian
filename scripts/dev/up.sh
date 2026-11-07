#!/usr/bin/env bash
# 启动本地基础设施（deploy/compose）：PostgreSQL、Redis、MinIO，等待全部健康、桶初始化完成。
# 口令与端口读取 deploy/compose/.env，不存在时使用 .env.example。PostgreSQL 镜像首次启动时构建。
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
compose_dir="${repo_root}/deploy/compose"
env_file="${compose_dir}/.env"
if [[ ! -f "${env_file}" ]]; then
    env_file="${compose_dir}/.env.example"
fi

compose=(docker compose --file "${compose_dir}/compose.yaml" --env-file "${env_file}")

"${compose[@]}" up --detach --build --wait postgres redis minio
# 一次性容器：前台运行，失败时脚本以非 0 退出
"${compose[@]}" up --no-log-prefix minio-init
"${compose[@]}" ps
