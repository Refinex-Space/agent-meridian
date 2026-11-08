#!/usr/bin/env bash
# 在 kind 集群部署沙箱：构建沙箱镜像并加载进集群，创建命名空间 meridian-sandbox（Pod Security restricted），
# 用 values-kind.yaml 安装 deploy/helm/meridian-sandbox，等待 sandbox-router 与预热池就绪。
#
# 用法：scripts/dev/deploy-sandbox.sh [集群名，默认 meridian] [--skip-build]
# 前提：scripts/dev/kind-gvisor.sh 与 scripts/dev/install-agent-sandbox.sh 已执行。
set -euo pipefail

CLUSTER="${1:-meridian}"
SKIP_BUILD="${2:-}"
NS="meridian-sandbox"
CTX="kind-${CLUSTER}"
K="kubectl --context ${CTX}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TAG="$(awk -F'"' '/^version = /{print $2; exit}' "$ROOT/sandbox-runtime/pyproject.toml")"
IMAGE="agent-meridian/sandbox-runtime:${TAG}"

if [ "$SKIP_BUILD" != "--skip-build" ]; then
  echo "==> 构建 ${IMAGE}"
  # 不生成 provenance 证明：带证明的镜像索引在 kind load 时会因缺少其他平台的内容而失败
  docker build -q --provenance=false -t "$IMAGE" "$ROOT/sandbox-runtime" >/dev/null
fi
echo "==> 加载镜像进集群 ${CLUSTER}"
kind load docker-image "$IMAGE" --name "$CLUSTER" >/dev/null

echo "==> 命名空间 ${NS}（Pod Security restricted）"
$K create namespace "$NS" --dry-run=client -o yaml | $K apply -f - >/dev/null
$K label namespace "$NS" --overwrite \
  pod-security.kubernetes.io/enforce=restricted pod-security.kubernetes.io/warn=restricted >/dev/null

echo "==> 安装 chart meridian-sandbox"
helm --kube-context "$CTX" upgrade --install meridian-sandbox "$ROOT/deploy/helm/meridian-sandbox" \
  -n "$NS" -f "$ROOT/deploy/helm/meridian-sandbox/values-kind.yaml" --set image.tag="$TAG" >/dev/null
$K -n "$NS" rollout status deployment/sandbox-router --timeout=180s >/dev/null

# 镜像标签不变时模板也不变，预热池里未被认领的沙箱仍是旧镜像：删除它们，由预热池按新镜像补充
STALE="$($K -n "$NS" get sandboxes -o json | python3 -c '
import json, sys
for item in json.load(sys.stdin)["items"]:
    owners = item["metadata"].get("ownerReferences", [])
    if any(o.get("kind") == "SandboxWarmPool" for o in owners):
        print(item["metadata"]["name"])
')"
if [ -n "$STALE" ]; then
  echo "==> 重建预热池中未认领的沙箱"
  # shellcheck disable=SC2086
  $K -n "$NS" delete sandboxes $STALE --wait=true >/dev/null
fi

for pool in $($K -n "$NS" get sandboxwarmpools -o jsonpath='{.items[*].metadata.name}'); do
  want="$($K -n "$NS" get sandboxwarmpool "$pool" -o jsonpath='{.spec.replicas}')"
  [ "$want" -gt 0 ] || continue
  echo "==> 等待预热池 ${pool} 就绪 ${want} 个"
  $K -n "$NS" wait "sandboxwarmpool/${pool}" --for=jsonpath='{.status.readyReplicas}'="$want" --timeout=300s >/dev/null
done
$K -n "$NS" get sandboxtemplates,sandboxwarmpools,pods
