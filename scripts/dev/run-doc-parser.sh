#!/usr/bin/env bash
# 在本机 Docker 中启动 doc-parser（T18，ADR-0051），宿主端口 18084（MERIDIAN_DOC_PARSER_PORT 可改），knowledge 的 dev profile 默认连它。
# 镜像不存在时先构建（首次约 10 分钟：Python 依赖与 Docling 的版面、表格、OCR 模型）；需要代理时设置 MERIDIAN_DOCKER_BUILD_PROXY，
# 例如 http://http.docker.internal:3128。内存：解析扫描件时约 2 GB。
# 用法：scripts/dev/run-doc-parser.sh；停止：docker rm -f meridian-doc-parser
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${repo_root}"

image="agent-meridian/doc-parser:dev"
if ! docker image inspect "${image}" >/dev/null 2>&1; then
    build_args=()
    if [[ -n "${MERIDIAN_DOCKER_BUILD_PROXY:-}" ]]; then
        build_args=(--build-arg "HTTP_PROXY=${MERIDIAN_DOCKER_BUILD_PROXY}" --build-arg "HTTPS_PROXY=${MERIDIAN_DOCKER_BUILD_PROXY}")
    fi
    docker build ${build_args[@]+"${build_args[@]}"} -t "${image}" doc-parser
fi

docker rm -f meridian-doc-parser >/dev/null 2>&1 || true
docker run -d --name meridian-doc-parser -p "127.0.0.1:${MERIDIAN_DOC_PARSER_PORT:-18084}:8000" "${image}" >/dev/null
echo "doc-parser 已启动：http://localhost:${MERIDIAN_DOC_PARSER_PORT:-18084}/healthz（停止：docker rm -f meridian-doc-parser）"
