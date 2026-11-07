#!/usr/bin/env bash
# 以 dev profile 在本机启动 runtime，连接 scripts/dev/up.sh 启动的 PostgreSQL 与 Redis。
# 口令与端口读取 deploy/compose/.env，不存在时使用 .env.example（与 up.sh 一致），只传给 Java 进程，不打印。
# 用法：scripts/dev/run-runtime.sh [Spring Boot 参数，例如 --server.port=18080]；Ctrl+C 停止。
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${repo_root}"

env_file="deploy/compose/.env"
if [[ ! -f "${env_file}" ]]; then
    env_file="deploy/compose/.env.example"
fi
set -a
# shellcheck disable=SC1090
source "${env_file}"
set +a
export MERIDIAN_DB_URL="${MERIDIAN_DB_URL:-jdbc:postgresql://localhost:${MERIDIAN_POSTGRES_PORT}/meridian}"

# 根 POM 从同一 reactor 导入 meridian-bom，所以带 -am 构建，再取 runtime 的运行时类路径
classpath_file="meridian-runtime/target/runtime.classpath"
./mvnw -q -B -pl meridian-runtime -am -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true \
    package dependency:build-classpath -Dmdep.outputFile=target/runtime.classpath -Dmdep.includeScope=runtime

# Netty 加载本地传输库需要 enable-native-access（登记事项 D06）
exec "${JAVA_HOME:-/usr}/bin/java" --enable-native-access=ALL-UNNAMED \
    -cp "meridian-runtime/target/classes:$(cat "${classpath_file}")" \
    cn.refinex.meridian.runtime.RuntimeApplication --spring.profiles.active=dev "$@"
