#!/usr/bin/env bash
# 验证 kind 集群具备沙箱所需的两项能力：gVisor 运行时生效、NetworkPolicy 被执行。
#
# 用法：scripts/dev/kind-verify.sh [集群名，默认 meridian]
# 通过时退出码为 0，并打印 PASS；任一项失败时退出码非 0。
set -euo pipefail

CLUSTER="${1:-meridian}"
CTX="kind-${CLUSTER}"
NS="meridian-verify"
K="kubectl --context ${CTX}"

cleanup() { $K delete namespace "$NS" --wait=false >/dev/null 2>&1 || true; }
trap cleanup EXIT

$K create namespace "$NS" >/dev/null 2>&1 || true

run_pod() { # run_pod <名称> <命令>，输出 Pod 日志最后一行
  $K -n "$NS" run "$1" --restart=Never --image=busybox:1.36 \
    --overrides='{"spec":{"runtimeClassName":"gvisor"}}' -- sh -c "$2" >/dev/null
  $K -n "$NS" wait --for=jsonpath='{.status.phase}'=Succeeded "pod/$1" --timeout=180s >/dev/null
  $K -n "$NS" logs "$1" | tail -1
}

KERNEL="$(run_pod gvisor-kernel 'uname -r')"
case "$KERNEL" in
  *gvisor*) echo "gVisor：PASS（内核 ${KERNEL}）" ;;
  *) echo "gVisor：FAIL（内核 ${KERNEL}，未运行在 gVisor 中）"; exit 1 ;;
esac

BEFORE="$(run_pod egress-before 'wget -q -T 5 -O /dev/null http://www.baidu.com && echo OPEN || echo BLOCKED')"
$K -n "$NS" apply -f - >/dev/null <<YAML
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: deny-all-egress
spec:
  podSelector: {}
  policyTypes: ["Egress"]
YAML
sleep 3
AFTER="$(run_pod egress-after 'wget -q -T 5 -O /dev/null http://www.baidu.com && echo OPEN || echo BLOCKED')"
if [ "$BEFORE" = "OPEN" ] && [ "$AFTER" = "BLOCKED" ]; then
  echo "NetworkPolicy：PASS（策略前 ${BEFORE}，拒绝全部出网后 ${AFTER}）"
else
  echo "NetworkPolicy：FAIL（策略前 ${BEFORE}，拒绝全部出网后 ${AFTER}）"; exit 1
fi
echo "PASS"
