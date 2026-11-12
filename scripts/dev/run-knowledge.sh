#!/usr/bin/env bash
# 以 dev profile 在本机启动 knowledge（端口 18083），连接 scripts/dev/up.sh 启动的 PostgreSQL、Redis 与 MinIO。
# 先启动 control-plane（API Key 校验、服务 JWT）与 runtime（Embedding，--server.port=18080）；扫描件、表格与图片还要 doc-parser
# （scripts/dev/run-doc-parser.sh，端口 18084）。客户端密钥取 env 文件的 MERIDIAN_KNOWLEDGE_CLIENT_SECRET。
# 口令与端口读取 deploy/compose/.env.example 与 .env（后者覆盖前者），只传给 Java 进程，不打印。
# 用法：scripts/dev/run-knowledge.sh [Spring Boot 参数]；Ctrl+C 停止。
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${repo_root}"

# 先读 .env.example 的默认值，再用 .env 覆盖：旧的 .env 里没有后来新增的变量（例如 T18 的 MERIDIAN_KNOWLEDGE_CLIENT_SECRET）时取示例值
set -a
# shellcheck disable=SC1091
source deploy/compose/.env.example
if [[ -f deploy/compose/.env ]]; then
    # shellcheck disable=SC1091
    source deploy/compose/.env
fi
set +a
export MERIDIAN_DB_URL="${MERIDIAN_DB_URL:-jdbc:postgresql://localhost:${MERIDIAN_POSTGRES_PORT}/meridian}"

# 根 POM 从同一 reactor 导入 meridian-bom，所以带 -am 构建，再取 knowledge 的运行时类路径
classpath_file="meridian-knowledge/target/runtime.classpath"
./mvnw -q -B -pl meridian-knowledge -am -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true \
    package dependency:build-classpath -Dmdep.outputFile=target/runtime.classpath -Dmdep.includeScope=runtime

exec "${JAVA_HOME:-/usr}/bin/java" \
    -cp "meridian-knowledge/target/classes:$(cat "${classpath_file}")" \
    cn.refinex.meridian.knowledge.KnowledgeApplication --spring.profiles.active=dev "$@"
