#!/usr/bin/env bash
# 在 kind 集群中安装 kubernetes-sigs/agent-sandbox 控制器（含扩展：SandboxTemplate、SandboxClaim、SandboxWarmPool），
# 使用官方发布清单 sandbox-with-extensions.yaml。
#
# 用法：scripts/dev/install-agent-sandbox.sh [集群名，默认 meridian]
# 环境变量：
#   MERIDIAN_AGENT_SANDBOX_VERSION   发布版本，默认 v1.0.10（架构设计「技术基线」锁定的版本）
#   MERIDIAN_DOWNLOAD_PROXY          下载发布物用的代理，默认 http://127.0.0.1:7897；设为空字符串表示直连
#
# 校验：默认版本的清单按 SHA-256 校验；任何版本都校验清单中控制器镜像的标签，安装后再核对运行中的镜像。
# sandbox-router 不在发布清单中，由 deploy/helm/meridian-sandbox 部署到沙箱命名空间。
set -euo pipefail

CLUSTER="${1:-meridian}"
VERSION="${MERIDIAN_AGENT_SANDBOX_VERSION:-v1.0.10}"
DOWNLOAD_PROXY="${MERIDIAN_DOWNLOAD_PROXY-http://127.0.0.1:7897}"
CTX="kind-${CLUSTER}"
K="kubectl --context ${CTX}"
IMAGE="registry.k8s.io/agent-sandbox/agent-sandbox-controller:${VERSION}"
known_sha256() { # 已登记版本的清单摘要（2026-11-08 下载）；macOS 自带的 bash 3.2 没有关联数组
  case "$1" in
    v1.0.10) echo 7cc1eca2fb45844b0ec0c35fc975340dc01af2994301ffa8bf05db1ad1e397ee ;;
  esac
}
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
MANIFEST="$WORK/sandbox-with-extensions.yaml"

URL="https://github.com/kubernetes-sigs/agent-sandbox/releases/download/${VERSION}/sandbox-with-extensions.yaml"
echo "==> 下载 agent-sandbox ${VERSION} 发布清单"
if [ -n "$DOWNLOAD_PROXY" ]; then curl -sSfL -x "$DOWNLOAD_PROXY" -o "$MANIFEST" "$URL"; else curl -sSfL -o "$MANIFEST" "$URL"; fi

EXPECTED="$(known_sha256 "$VERSION")"
if [ -n "$EXPECTED" ]; then
  ACTUAL="$(shasum -a 256 "$MANIFEST" | awk '{print $1}')"
  [ "$ACTUAL" = "$EXPECTED" ] || { echo "清单 SHA-256 不符：${ACTUAL}" >&2; exit 1; }
else
  echo "    ${VERSION} 没有登记的 SHA-256，只校验镜像标签"
fi
grep -q "image: ${IMAGE}$" "$MANIFEST" || { echo "清单中的控制器镜像不是 ${IMAGE}" >&2; exit 1; }

echo "==> 安装控制器与 CRD（server-side apply：CRD 超过客户端 apply 的注解上限）"
$K apply --server-side --force-conflicts -f "$MANIFEST" >/dev/null
for crd in sandboxes.agents.x-k8s.io sandboxclaims.extensions.agents.x-k8s.io \
  sandboxtemplates.extensions.agents.x-k8s.io sandboxwarmpools.extensions.agents.x-k8s.io; do
  $K wait --for=condition=Established "crd/${crd}" --timeout=60s >/dev/null
done
$K -n agent-sandbox-system rollout status deployment/agent-sandbox-controller --timeout=300s

RUNNING="$($K -n agent-sandbox-system get deployment agent-sandbox-controller \
  -o jsonpath='{.spec.template.spec.containers[0].image}')"
[ "$RUNNING" = "$IMAGE" ] || { echo "运行中的控制器镜像是 ${RUNNING}，期望 ${IMAGE}" >&2; exit 1; }
echo "==> 完成：agent-sandbox ${VERSION}（${RUNNING}）"
echo "    部署沙箱模板与预热池：scripts/dev/deploy-sandbox.sh ${CLUSTER}"
