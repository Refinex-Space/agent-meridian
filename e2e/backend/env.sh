#!/usr/bin/env bash
# M1 后端验收预演（R2）的本机环境：在宿主上以 dev profile 启动全部服务，进程与日志在 e2e/backend/.run/（已忽略）。
#   up                    启动基础设施（scripts/dev/up.sh）、Keycloak（Compose auth profile）、脚本化模型服务（加载
#                         e2e/backend/model-scripts/ 的副本 .run/model-scripts/）、control-plane、两个 runtime 节点、knowledge；
#                         已在监听的端口跳过
#   down                  停止本脚本启动的 Java 进程与脚本化模型服务（基础设施与 Keycloak 用 scripts/dev/down.sh 停）
#   status                各服务的健康状态
#   start-runtime a|b     启动一个 runtime 节点（场景 6 杀掉节点后由 m1_backend.py 调用）
#   kill-runtime a|b      强杀一个 runtime 节点（kill -9，模拟节点崩溃）
# runtime 节点：a 在 18080（knowledge 的 Embedding 与 kind 中出网代理的回调都指向它），b 在 18086；两个节点的智能体配置与
# 模型目录都来自 control-plane，连 kind 集群 meridian 的沙箱（E2E_SANDBOX=false 时不连，场景 2 随之跳过）。
# 只打印端口与状态，不打印口令、密钥与令牌（口令从 deploy/compose/.env 或 .env.example 传给子进程）。
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${repo_root}"
run_dir="e2e/backend/.run"
mkdir -p "${run_dir}"

set -a
# shellcheck disable=SC1091
source deploy/compose/.env.example
if [[ -f deploy/compose/.env ]]; then
    # shellcheck disable=SC1091
    source deploy/compose/.env
fi
set +a
export MERIDIAN_DB_URL="${MERIDIAN_DB_URL:-jdbc:postgresql://localhost:${MERIDIAN_POSTGRES_PORT}/meridian}"

sandbox="${E2E_SANDBOX:-true}"
java="${JAVA_HOME:-/usr}/bin/java"

listening() {
    lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}

wait_http() {
    local name="$1" url="$2" seconds="${3:-180}"
    for _ in $(seq 1 "${seconds}"); do
        if curl -sf --noproxy '*' -o /dev/null "${url}"; then
            echo "PASS ${name} 就绪（${url}）"
            return 0
        fi
        sleep 1
    done
    echo "FAIL ${name} 在 ${seconds} 秒内没有就绪，日志见 ${run_dir}/${name}.log" >&2
    return 1
}

wait_port() {
    local name="$1" port="$2" seconds="${3:-120}"
    for _ in $(seq 1 "${seconds}"); do
        if listening "${port}"; then
            echo "PASS ${name} 就绪（端口 ${port}）"
            return 0
        fi
        sleep 1
    done
    echo "FAIL ${name} 在 ${seconds} 秒内没有监听 ${port}，日志见 ${run_dir}/${name}.log" >&2
    return 1
}

# 在后台启动一个进程，记下 PID；标准输出与错误写进 .run/<名字>.log
spawn() {
    local name="$1"
    shift
    nohup "$@" >"${run_dir}/${name}.log" 2>&1 &
    echo $! >"${run_dir}/${name}.pid"
}

runtime_port() {
    case "$1" in
        a) echo 18080 ;;
        b) echo 18086 ;;
        *) echo "未知节点：$1（a 或 b）" >&2; exit 2 ;;
    esac
}

# 构建 runtime 的类路径（只在 start-runtime 之外调用一次，两个节点共用）
build_runtime() {
    ./mvnw -q -B -pl meridian-runtime -am -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true \
        package dependency:build-classpath -Dmdep.outputFile=target/runtime.classpath -Dmdep.includeScope=runtime
}

start_runtime() {
    local node="$1" port
    port="$(runtime_port "${node}")"
    if listening "${port}"; then
        echo "SKIP runtime-${node} 已在 ${port} 监听"
        return 0
    fi
    local args=(--spring.profiles.active=dev "--server.port=${port}" "--meridian.runtime.dispatch.node-id=e2e-${node}"
        --management.endpoints.web.exposure.include=health,metrics)
    if [[ "${sandbox}" == true ]]; then
        args+=(--meridian.runtime.sandbox.enabled=true --meridian.runtime.sandbox.kube-context=kind-meridian)
    fi
    MERIDIAN_RUNTIME_AGENT_CONFIG_SOURCE=control-plane MERIDIAN_RUNTIME_CATALOG_SOURCE=control-plane \
        spawn "runtime-${node}" "${java}" --enable-native-access=ALL-UNNAMED \
        -cp "meridian-runtime/target/classes:$(cat meridian-runtime/target/runtime.classpath)" \
        cn.refinex.meridian.runtime.RuntimeApplication "${args[@]}"
    wait_http "runtime-${node}" "http://localhost:${port}/actuator/health"
}

kill_runtime() {
    local node="$1" port pids
    port="$(runtime_port "${node}")"
    pids="$(lsof -nP -t -iTCP:"${port}" -sTCP:LISTEN || true)"
    if [[ -z "${pids}" ]]; then
        echo "SKIP runtime-${node} 没有在 ${port} 监听"
        return 0
    fi
    # shellcheck disable=SC2086
    kill -9 ${pids}
    echo "PASS 已强杀 runtime-${node}（端口 ${port}）"
}

up() {
    scripts/dev/up.sh
    docker compose -f deploy/compose/compose.yaml --env-file "$( [[ -f deploy/compose/.env ]] && echo deploy/compose/.env \
        || echo deploy/compose/.env.example)" --profile auth up -d keycloak >/dev/null
    wait_http keycloak "http://localhost:${MERIDIAN_KEYCLOAK_PORT:-18082}/realms/meridian-dev" 180

    if listening 18090; then
        echo "SKIP 脚本化模型服务已在 18090 监听（要加载 e2e/backend/model-scripts，先停掉它）"
    else
        # 服务加载 .run/model-scripts（场景脚本的副本）：m1_backend.py 会把按本次运行生成的脚本写进去，服务遇到不认识的脚本名时重新读目录
        rm -rf "${run_dir}/model-scripts"
        cp -R e2e/backend/model-scripts "${run_dir}/model-scripts"
        spawn scripted-model scripts/dev/scripted-model.sh --port=18090 --scripts="${run_dir}/model-scripts"
        wait_port scripted-model 18090 120
    fi
    if listening 18081; then
        echo "SKIP control-plane 已在 18081 监听"
    else
        spawn control-plane scripts/dev/run-control-plane.sh
        wait_http control-plane "http://localhost:18081/actuator/health" 300
    fi
    build_runtime
    start_runtime a
    start_runtime b
    if listening 18083; then
        echo "SKIP knowledge 已在 18083 监听"
    else
        spawn knowledge scripts/dev/run-knowledge.sh
        wait_http knowledge "http://localhost:18083/actuator/health" 300
    fi
}

down() {
    for pid_file in "${run_dir}"/*.pid; do
        [[ -f "${pid_file}" ]] || continue
        local pid
        pid="$(cat "${pid_file}")"
        if kill -0 "${pid}" 2>/dev/null; then
            # 脚本启动的进程先 exec 成 java，脚本化模型服务的子进程随进程组退出
            kill "${pid}" 2>/dev/null || true
        fi
        rm -f "${pid_file}"
    done
    for port in 18080 18086 18083 18081 18090; do
        local pids
        pids="$(lsof -nP -t -iTCP:"${port}" -sTCP:LISTEN || true)"
        # shellcheck disable=SC2086
        [[ -z "${pids}" ]] || kill ${pids} 2>/dev/null || true
    done
    echo "已停止 e2e 的服务进程（基础设施与 Keycloak 用 scripts/dev/down.sh）"
}

status() {
    local name url
    for pair in "control-plane=http://localhost:18081/actuator/health" "runtime-a=http://localhost:18080/actuator/health" \
        "runtime-b=http://localhost:18086/actuator/health" "knowledge=http://localhost:18083/actuator/health" \
        "scripted-model=http://localhost:18090/"; do
        name="${pair%%=*}"
        url="${pair#*=}"
        if curl -s --noproxy '*' -o /dev/null "${url}"; then echo "UP   ${name}"; else echo "DOWN ${name}"; fi
    done
}

case "${1:-}" in
    up) up ;;
    down) down ;;
    status) status ;;
    start-runtime) start_runtime "${2:?节点 a 或 b}" ;;
    kill-runtime) kill_runtime "${2:?节点 a 或 b}" ;;
    *) echo "用法：e2e/backend/env.sh up|down|status|start-runtime a|b|kill-runtime a|b" >&2; exit 2 ;;
esac
