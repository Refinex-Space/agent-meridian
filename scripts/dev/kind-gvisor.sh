#!/usr/bin/env bash
# 创建本地 kind 集群：Calico 网络插件（执行 NetworkPolicy）+ gVisor（runsc）运行时与 RuntimeClass "gvisor"。
#
# 用法：scripts/dev/kind-gvisor.sh [集群名，默认 meridian]
# 环境变量：
#   MERIDIAN_GVISOR_RELEASE     gVisor 发布日期版本，默认 20261102
#   MERIDIAN_CALICO_VERSION     Calico 版本，默认 v3.33.0
#   MERIDIAN_DOWNLOAD_PROXY     宿主机下载发布物用的代理，默认 http://127.0.0.1:7897；设为空字符串表示直连
#   MERIDIAN_KIND_NODE_PROXY    节点拉取镜像用的代理，默认 Docker Desktop 内置代理 http://http.docker.internal:3128
#   MERIDIAN_KIND_POD_SUBNET    Pod 网段（Calico 的 IP 池），默认 10.244.0.0/16
#
# 设计说明：
# - kind 节点是 Docker Desktop Linux 虚拟机里的容器。gVisor 以 systrap 平台运行，不需要 KVM，
#   因此在 macOS 上也能得到真实的 gVisor 隔离（Pod 内 uname 显示 *-gvisor 内核）。
# - kind 默认的 kindnet 不执行 NetworkPolicy（2026-11-07 实测），出网控制必须使用 Calico。
# - 节点只使用 MERIDIAN_KIND_NODE_PROXY，不继承宿主 shell 的代理变量：Claude Code 工具沙箱会注入
#   只在宿主回环地址可用的代理，节点内无法访问，会导致镜像拉取失败。
# - 所有下载都校验摘要（gVisor 用官方 SHA-512）。
# - Pod 网段不能与 Docker Desktop 的宿主地址（host.docker.internal = 192.168.65.254）重叠：Calico 对 IP 池内的目的地址
#   不做出向 NAT，用 Calico 默认的 192.168.0.0/16 时 Pod 访问不到宿主，跑在宿主 JVM 的 runtime 收不到出网代理的授权回调（T12b）。
set -euo pipefail

CLUSTER="${1:-meridian}"
GVISOR_RELEASE="${MERIDIAN_GVISOR_RELEASE:-20261102}"
CALICO_VERSION="${MERIDIAN_CALICO_VERSION:-v3.33.0}"
DOWNLOAD_PROXY="${MERIDIAN_DOWNLOAD_PROXY-http://127.0.0.1:7897}"
NODE_PROXY="${MERIDIAN_KIND_NODE_PROXY:-http://http.docker.internal:3128}"
POD_SUBNET="${MERIDIAN_KIND_POD_SUBNET:-10.244.0.0/16}"
CTX="kind-${CLUSTER}"
ARCH="$(uname -m)"; [ "$ARCH" = "arm64" ] && ARCH=aarch64
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

dl() { # dl <url> <输出文件>
  if [ -n "$DOWNLOAD_PROXY" ]; then curl -sSfL -x "$DOWNLOAD_PROXY" -o "$2" "$1"; else curl -sSfL -o "$2" "$1"; fi
}

echo "==> 下载 gVisor ${GVISOR_RELEASE} (${ARCH})"
URL="https://storage.googleapis.com/gvisor/releases/release/${GVISOR_RELEASE}/${ARCH}"
dl "$URL/gvisor.tar.bz2" "$WORK/gvisor.tar.bz2"
dl "$URL/gvisor.tar.bz2.sha512" "$WORK/gvisor.tar.bz2.sha512"
[ "$(awk '{print $1}' "$WORK/gvisor.tar.bz2.sha512")" = "$(shasum -a 512 "$WORK/gvisor.tar.bz2" | awk '{print $1}')" ] \
  || { echo "gVisor SHA-512 校验失败" >&2; exit 1; }
mkdir -p "$WORK/x" && tar -xjf "$WORK/gvisor.tar.bz2" -C "$WORK/x"

echo "==> 下载 Calico ${CALICO_VERSION} 清单"
dl "https://raw.githubusercontent.com/projectcalico/calico/${CALICO_VERSION}/manifests/calico.yaml" "$WORK/calico.yaml"
# 清单默认注释掉 IP 池网段（等于 192.168.0.0/16），改为与 kubeadm podSubnet 一致
sed -i.orig -e 's|# - name: CALICO_IPV4POOL_CIDR|- name: CALICO_IPV4POOL_CIDR|' \
  -e "s|#   value: \"192.168.0.0/16\"|  value: \"${POD_SUBNET}\"|" "$WORK/calico.yaml"
grep -A1 -- '- name: CALICO_IPV4POOL_CIDR' "$WORK/calico.yaml" | grep -q "\"${POD_SUBNET}\"" \
  || { echo "无法在 Calico 清单中设置 IP 池网段" >&2; exit 1; }

if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  echo "==> 创建 kind 集群 ${CLUSTER}（禁用默认 CNI）"
  cat > "$WORK/kind.yaml" <<YAML
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
networking:
  disableDefaultCNI: true
  podSubnet: "${POD_SUBNET}"
YAML
  env -u ALL_PROXY -u all_proxy \
    HTTP_PROXY="$NODE_PROXY" HTTPS_PROXY="$NODE_PROXY" http_proxy="$NODE_PROXY" https_proxy="$NODE_PROXY" \
    NO_PROXY="localhost,127.0.0.1,::1,.svc,.cluster.local,10.0.0.0/8,172.16.0.0/12,${POD_SUBNET}" \
    kind create cluster --name "$CLUSTER" --config "$WORK/kind.yaml"
  echo "==> 安装 Calico"
  kubectl --context "$CTX" apply -f "$WORK/calico.yaml" >/dev/null
  kubectl --context "$CTX" -n kube-system rollout status daemonset/calico-node --timeout=300s
  kubectl --context "$CTX" wait --for=condition=Ready nodes --all --timeout=180s
fi

for NODE in $(kind get nodes --name "$CLUSTER"); do
  echo "==> 在节点 ${NODE} 安装 runsc"
  docker cp "$WORK/x/runsc" "$NODE:/usr/local/bin/runsc"
  docker cp "$WORK/x/containerd-shim-runsc-v1" "$NODE:/usr/local/bin/containerd-shim-runsc-v1"
  docker exec "$NODE" mkdir -p /usr/local/bin/gvisor-bin
  # runsc 在自身所在目录的 gvisor-bin/ 下查找 sentry 等伴随程序
  for f in "$WORK"/x/gvisor-bin/*; do docker cp "$f" "$NODE:/usr/local/bin/gvisor-bin/"; done
  docker exec "$NODE" sh -c 'chmod +x /usr/local/bin/runsc /usr/local/bin/containerd-shim-runsc-v1 /usr/local/bin/gvisor-bin/*'
  if ! docker exec "$NODE" grep -q 'runtimes.runsc' /etc/containerd/config.toml; then
    docker exec "$NODE" sh -c 'printf "\n[plugins.\"io.containerd.grpc.v1.cri\".containerd.runtimes.runsc]\n  runtime_type = \"io.containerd.runsc.v1\"\n" >> /etc/containerd/config.toml'
    docker exec "$NODE" systemctl restart containerd
  fi
done

kubectl --context "$CTX" apply -f - >/dev/null <<YAML
apiVersion: node.k8s.io/v1
kind: RuntimeClass
metadata:
  name: gvisor
handler: runsc
YAML

echo "==> 完成：集群 ${CLUSTER}，上下文 ${CTX}，RuntimeClass gvisor，网络插件 Calico ${CALICO_VERSION}"
echo "    验证 gVisor：scripts/dev/kind-verify.sh ${CLUSTER}"
