#!/usr/bin/env bash
# 独立启动脚本化模型服务 ScriptedOpenAiServer（T09b）：OpenAI 兼容的本地假供应商，dev profile 的 runtime 经
# dev-config/providers/scripted.yaml 调用它（模型 mdl_dev_scripted、mdl_dev_scripted_embedding、mdl_dev_scripted_rerank）。
#
# 用法：scripts/dev/scripted-model.sh [--port=18090] [--host=127.0.0.1] [--scripts=<目录>]
#   默认端口 18090，与 scripted.yaml 一致；--scripts 加载额外的 YAML 脚本（同名覆盖内置）。前台运行，Ctrl-C 停止。
# 不读取任何密钥，不访问外网。
set -euo pipefail
cd "$(dirname "$0")/../.."

port=18090
for arg in "$@"; do
    case "${arg}" in
        --port=*) port="${arg#--port=}" ;;
    esac
done

# 根 POM 从同一 reactor 导入 meridian-bom，所以带 -am 构建，再取 test-support 的运行时类路径
classpath_file="meridian-test-support/target/scripted-model.classpath"
./mvnw -q -B -pl meridian-test-support -am -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true \
    package dependency:build-classpath -Dmdep.outputFile=target/scripted-model.classpath -Dmdep.includeScope=runtime

"${JAVA_HOME:-/usr}/bin/java" \
    -cp "meridian-test-support/target/classes:$(cat "${classpath_file}")" \
    cn.refinex.meridian.testsupport.model.ScriptedOpenAiServer "$@" &
server_pid=$!
trap 'kill "${server_pid}" 2>/dev/null || true' EXIT INT TERM

for _ in $(seq 1 50); do
    if curl -s -o /dev/null --noproxy '*' "http://127.0.0.1:${port}/v1/models" 2>/dev/null; then
        echo "ScriptedOpenAiServer 已启动：http://127.0.0.1:${port}/v1（Ctrl-C 停止）"
        break
    fi
    if ! kill -0 "${server_pid}" 2>/dev/null; then
        echo "ScriptedOpenAiServer 启动失败" >&2
        exit 1
    fi
    sleep 0.2
done
wait "${server_pid}"
