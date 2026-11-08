#!/bin/sh
# 沙箱容器入口：准备 /tmp 下的 HOME 与缓存目录（根文件系统只读），再启动执行服务。
set -eu

mkdir -p "$HOME" "$MPLCONFIGDIR" "$XDG_CACHE_HOME"
for f in /opt/meridian/mplcache/*.json; do
  if [ -e "$f" ] && [ ! -e "$MPLCONFIGDIR/$(basename "$f")" ]; then
    cp "$f" "$MPLCONFIGDIR/"
  fi
done

exec python -m uvicorn sandbox_runtime.app:app \
  --host 0.0.0.0 --port 8888 --no-access-log --timeout-graceful-shutdown 5
