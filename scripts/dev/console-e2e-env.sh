#!/usr/bin/env bash
# 控制台端到端测试的后端（T21）：Compose 的 app 与 auth profile 中控制台用到的服务——control-plane、runtime、knowledge、
# 脚本化模型服务、Keycloak（及其依赖的 PostgreSQL、Redis、MinIO）。doc-parser 不启动（用例不上传文档）。runtime 的智能体配置与
# 模型目录读 control-plane（T22：控制台发布的智能体要能创建会话），模型由全局准备经管理 API 登记。本机与 CI 共用。
#
# 用法：
#   scripts/dev/console-e2e-env.sh up [--no-build]   Jib 构建 control-plane、runtime、knowledge、脚本化模型服务的镜像，启动并等待健康
#   scripts/dev/console-e2e-env.sh down              停止这些服务（数据卷保留；删除数据用 scripts/dev/down.sh --volumes）
# 之后在 console/ 中运行 pnpm e2e:app。
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${repo_root}"

# 先读 .env.example，再用本地 .env 覆盖（旧的 .env 不必补新增的变量，例如 knowledge 的客户端密钥）
env_files=(--env-file deploy/compose/.env.example)
[[ -f deploy/compose/.env ]] && env_files+=(--env-file deploy/compose/.env)
compose=(docker compose -f deploy/compose/compose.yaml "${env_files[@]}" --profile app --profile auth)
services=(keycloak control-plane runtime knowledge scripted-model)
export MERIDIAN_RUNTIME_AGENT_CONFIG_SOURCE=control-plane MERIDIAN_RUNTIME_CATALOG_SOURCE=control-plane

command="${1:-}"
shift || true
case "${command}" in
    up)
        build=true
        for arg in "$@"; do
            case "${arg}" in
                --no-build) build=false ;;
                *) echo "未知参数：${arg}" >&2; exit 2 ;;
            esac
        done
        if [[ "${build}" == true ]]; then
            docker image inspect eclipse-temurin:25-jre >/dev/null 2>&1 || docker pull eclipse-temurin:25-jre
            # -am 带上依赖模块（它们的 jib.skip 为 true，不打镜像）
            ./mvnw -B -ntp -pl meridian-control-plane,meridian-runtime,meridian-knowledge,meridian-test-support -am \
                -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true package jib:dockerBuild
        fi
        "${compose[@]}" up -d --wait --wait-timeout 300 "${services[@]}"
        echo "控制台端到端的后端已就绪：control-plane :18081、runtime :18080、knowledge :18083、Keycloak :18082、脚本化模型服务 :18090"
        ;;
    down)
        "${compose[@]}" stop "${services[@]}"
        ;;
    *)
        echo "用法：$0 up [--no-build] | down" >&2
        exit 2
        ;;
esac
