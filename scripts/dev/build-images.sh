#!/usr/bin/env bash
# 用 Jib 构建 Compose app profile 的三个开发镜像，写入本机 Docker（T14a）：
#   agent-meridian/control-plane:dev、agent-meridian/runtime:dev、agent-meridian/scripted-model:dev
# 基础镜像 eclipse-temurin:25-jre 先拉到本机 Docker，Jib 从本机读取（根 POM 的 jib-maven-plugin 配置）。
# 用法：scripts/dev/build-images.sh；之后 docker compose -f deploy/compose/compose.yaml --profile app up -d。
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${repo_root}"

docker image inspect eclipse-temurin:25-jre >/dev/null 2>&1 || docker pull eclipse-temurin:25-jre

# -am 带上依赖模块（它们的 jib.skip 为 true，不打镜像）
./mvnw -B -ntp -pl meridian-control-plane,meridian-runtime,meridian-test-support -am \
    -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true package jib:dockerBuild
