#!/usr/bin/env bash
# runtime 的容量与内存压测（R1：P7、P11），用法与结果见 docs/poc/P07-容量.md、docs/poc/P11-内存.md。
# 前提：scripts/dev/up.sh；scripts/dev/scripted-model.sh --scripts=scripts/perf/model-scripts；
#       scripts/dev/run-control-plane.sh；scripts/dev/dev-api-key.sh（签发不限流的开发 Key，写入 .env）；
#       scripts/dev/run-runtime.sh --server.port=18080 --management.endpoints.web.exposure.include=health,metrics
# 用法：scripts/perf/capacity.sh turns|sse|rotate [--key=value ...]（参数见 CapacityDriver.java 的类注释）
# API Key 读 deploy/compose/.env 的 MERIDIAN_DEV_API_KEY（由 dev-api-key.sh 写入），只传给驱动进程，不打印。
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${repo_root}"

env_file="deploy/compose/.env"
MERIDIAN_DEV_API_KEY="$(grep -E '^MERIDIAN_DEV_API_KEY=' "${env_file}" 2>/dev/null | cut -d= -f2- || true)"
if [[ -z "${MERIDIAN_DEV_API_KEY}" ]]; then
    echo "deploy/compose/.env 中没有 MERIDIAN_DEV_API_KEY：先运行 scripts/dev/dev-api-key.sh" >&2
    exit 1
fi
export MERIDIAN_DEV_API_KEY

exec "${JAVA_HOME:-/usr}/bin/java" -Xmx2g scripts/perf/CapacityDriver.java "$@"
