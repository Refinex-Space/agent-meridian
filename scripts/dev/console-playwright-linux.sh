#!/usr/bin/env bash
# 在 Playwright 官方 Linux 镜像中运行控制台的 Playwright 用例（T21）：截图基线按平台分开，CI 与本机生成 Linux 基线都用同一个
# 镜像，渲染结果一致。控制台源码复制进容器再安装依赖，不改动本机的 node_modules。
#
# 用法：
#   scripts/dev/console-playwright-linux.sh baseline [--update-snapshots]
#       外壳与四种页面模板的截图基线（e2e/shell-baseline.spec.ts，跑在构建后的 Storybook 上）；
#       --update-snapshots 时把生成的 *-linux.png 写回 console/e2e/shell-baseline.spec.ts-snapshots/
#   scripts/dev/console-playwright-linux.sh app
#       控制台的端到端用例（pnpm e2e:app），连接宿主上的后端（先 scripts/dev/console-e2e-env.sh up），用宿主网络，只在 Linux 上可用
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${repo_root}"

# 与 console/package.json 中 @playwright/test 的版本一致。
image="mcr.microsoft.com/playwright:v1.64.1-noble"

suite="${1:-}"
shift || true
update=false
for arg in "$@"; do
    case "${arg}" in
        --update-snapshots) update=true ;;
        *) echo "未知参数：${arg}" >&2; exit 2 ;;
    esac
done

case "${suite}" in
    baseline)
        command="pnpm e2e e2e/shell-baseline.spec.ts"
        [[ "${update}" == true ]] && command="${command} --update-snapshots"
        network=()
        ;;
    app)
        command="pnpm e2e:app"
        network=(--network host)
        ;;
    *)
        echo "用法：$0 baseline [--update-snapshots] | app" >&2
        exit 2
        ;;
esac

snapshots="console/e2e/shell-baseline.spec.ts-snapshots"
mkdir -p "${snapshots}" console/test-results

# 只复制控制台与它生成类型所读的 OpenAPI 规范；构建产物与依赖在容器里重新生成。
# 不把宿主的代理变量带进容器（工具沙箱注入的代理只在宿主回环地址可用）。
docker run --rm "${network[@]+"${network[@]}"}" \
    -e CI=true \
    -v "${repo_root}:/src:ro" \
    -v "${repo_root}/${snapshots}:/snapshots" \
    -v "${repo_root}/console/test-results:/results" \
    "${image}" bash -euo pipefail -c "
        mkdir -p /work
        tar -C /src --exclude=console/node_modules --exclude=console/storybook-static --exclude=console/dist \
            --exclude=console/test-results -cf - console meridian-api/src/main/resources/openapi | tar -C /work -xf -
        cd /work/console
        # 镜像里的 Node 不带 corepack：按 packageManager 字段安装同版本的 pnpm
        npm install -g --silent \"\$(node -p \"require('./package.json').packageManager\")\"
        pnpm install --frozen-lockfile --silent
        status=0
        ${command} || status=\$?
        cp -r test-results/. /results/ 2>/dev/null || true
        if [[ ${update} == true ]]; then cp e2e/shell-baseline.spec.ts-snapshots/*-linux.png /snapshots/; fi
        exit \${status}
    "
