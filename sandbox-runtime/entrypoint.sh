#!/bin/bash
# 沙箱容器入口：设置进程数上限，准备 /tmp 下的 HOME 与缓存目录（根文件系统只读），再启动执行服务。
set -euo pipefail

# 进程数上限（沙箱规格的"进程数"）。gVisor 中每个 Pod 有自己的内核，RLIMIT_NPROC 只统计本沙箱的进程
if [ -n "${SANDBOX_MAX_PROCESSES:-}" ]; then
  ulimit -u "$SANDBOX_MAX_PROCESSES"
fi

mkdir -p "$HOME" "$MPLCONFIGDIR" "$XDG_CACHE_HOME"
for f in /opt/meridian/mplcache/*.json; do
  if [ -e "$f" ] && [ ! -e "$MPLCONFIGDIR/$(basename "$f")" ]; then
    cp "$f" "$MPLCONFIGDIR/"
  fi
done

exec python -m uvicorn sandbox_runtime.app:app \
  --host 0.0.0.0 --port 8888 --no-access-log --timeout-graceful-shutdown 5
