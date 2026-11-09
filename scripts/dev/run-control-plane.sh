#!/usr/bin/env bash
# 以 dev profile 在本机启动 control-plane（端口 18081），连接 scripts/dev/up.sh 启动的 PostgreSQL 与 Redis。
# 口令与开发期令牌读取 deploy/compose/.env，不存在时使用 .env.example（与 up.sh 一致），只传给 Java 进程，不打印。
# 用法：scripts/dev/run-control-plane.sh [Spring Boot 参数]；Ctrl+C 停止。管理 API 用 MERIDIAN_DEV_CONSOLE_TOKEN（主体 dev-admin）。
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

# 根 POM 从同一 reactor 导入 meridian-bom，所以带 -am 构建，再取 control-plane 的运行时类路径
classpath_file="meridian-control-plane/target/control-plane.classpath"
./mvnw -q -B -pl meridian-control-plane -am -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true \
    package dependency:build-classpath -Dmdep.outputFile=target/control-plane.classpath -Dmdep.includeScope=runtime

exec "${JAVA_HOME:-/usr}/bin/java" \
    -cp "meridian-control-plane/target/classes:$(cat "${classpath_file}")" \
    cn.refinex.meridian.controlplane.ControlPlaneApplication --spring.profiles.active=dev "$@"
