#!/usr/bin/env bash
# 全量质量检查：格式、编译、单元测试、集成测试、架构测试、依赖规则、覆盖率。
# 任何一项失败都以非 0 退出。后续任务会在这里追加前端与其他 Python 子项目的检查。
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${repo_root}"

./mvnw -B -ntp spotless:check verify
scripts/ci/check-pom-versions.sh

# sandbox-runtime（Python）：格式、静态检查、类型检查、单元测试；内置技能的脚本（skills/builtin，在沙箱中运行）用同一套 Ruff 规则
(
  cd sandbox-runtime
  uv sync --frozen --quiet
  uv run --frozen ruff format --check .
  uv run --frozen ruff check .
  uv run --frozen ruff format --check --config pyproject.toml ../skills
  uv run --frozen ruff check --config pyproject.toml ../skills
  uv run --frozen mypy
  uv run --frozen pytest -q -W error
)

# doc-parser（Python，T18）：格式、静态检查、类型检查、单元测试；依赖固化模型的用例（标记 models）只在镜像的 test 阶段运行
(
  cd doc-parser
  uv sync --frozen --quiet
  uv run --frozen ruff format --check .
  uv run --frozen ruff check .
  uv run --frozen mypy
  uv run --frozen pytest -q -W error
)

# console（T20a）：lint（ESLint 与守护规则、样式表检查、Prettier、OpenAPI 生成类型是否过期）、类型检查、单元测试、Storybook 构建。
# Playwright（时间轴性能、动效、无障碍）不在这里：需要浏览器，单独用 pnpm -C console e2e 运行。
pnpm -C console install --frozen-lockfile --silent
pnpm -C console lint
pnpm -C console typecheck
pnpm -C console test
pnpm -C console build-storybook
