#!/usr/bin/env bash
# 全量质量检查：格式、编译、单元测试、集成测试、架构测试、依赖规则、覆盖率。
# 任何一项失败都以非 0 退出。后续任务会在这里追加前端与其他 Python 子项目的检查。
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${repo_root}"

./mvnw -B -ntp spotless:check verify
scripts/ci/check-pom-versions.sh

# sandbox-runtime（Python）：格式、静态检查、类型检查、单元测试
(
  cd sandbox-runtime
  uv sync --frozen --quiet
  uv run --frozen ruff format --check .
  uv run --frozen ruff check .
  uv run --frozen mypy
  uv run --frozen pytest -q -W error
)
