# AgentMeridian 沙箱镜像：Debian slim、非 root（uid 1000）、工作目录 /workspace，
# uv 管理的 Python 3.13（执行服务与文档处理库在同一个虚拟环境）、Node.js 24，执行服务监听 8888。
# 可在 linux/arm64 与 linux/amd64 上构建（不含架构相关的下载地址）。
#
#   构建：docker build -t agent-meridian/sandbox-runtime:0.1.0 sandbox-runtime
#   测试：docker build --target test sandbox-runtime     （以 uid 1000 在镜像内跑 pytest）
#
# 运行时由沙箱模板把根文件系统设为只读，/var/lib/sandbox 与 /tmp 挂 emptyDir；HOME 与各类缓存都在 /tmp 下。
# /workspace 是指向 /var/lib/sandbox/workspace 的符号链接，目录由入口脚本以 uid 1000 创建：emptyDir 的挂载点属于 root，
# 非 root 用户不能修改它的时间与权限，而 AgentScope 恢复快照时 tar -xf 会设置归档根目录 "." 的时间与权限。
ARG DEBIAN_IMAGE=debian:trixie-slim
ARG NODE_IMAGE=node:24-trixie-slim
ARG UV_IMAGE=ghcr.io/astral-sh/uv:0.11.8

FROM ${UV_IMAGE} AS uv
FROM ${NODE_IMAGE} AS node

FROM ${DEBIAN_IMAGE} AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash ca-certificates curl git jq procps tini unzip xz-utils \
        fonts-dejavu-core fonts-wqy-microhei \
    && rm -rf /var/lib/apt/lists/*

COPY --from=uv /uv /uvx /usr/local/bin/
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx

ENV UV_PYTHON_INSTALL_DIR=/opt/meridian/python \
    UV_PYTHON_PREFERENCE=only-managed \
    UV_PROJECT_ENVIRONMENT=/opt/meridian/venv \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy
WORKDIR /opt/meridian/service
COPY pyproject.toml uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv \
    uv python install 3.13 \
    && uv sync --frozen --no-dev --group toolchain
# 预先生成 matplotlib 字体缓存，启动时复制到可写的 MPLCONFIGDIR，首次绘图不再扫描字体
RUN MPLCONFIGDIR=/opt/meridian/mplcache /opt/meridian/venv/bin/python -c "import matplotlib.font_manager"
COPY src ./src
COPY entrypoint.sh /usr/local/bin/sandbox-entrypoint

RUN groupadd --gid 1000 sandbox \
    && useradd --uid 1000 --gid 1000 --home-dir /tmp/home --no-create-home --shell /bin/bash sandbox \
    && mkdir -p /var/lib/sandbox && ln -s /var/lib/sandbox/workspace /workspace

ENV PATH=/opt/meridian/venv/bin:/usr/local/bin:/usr/bin:/bin \
    PYTHONPATH=/opt/meridian/service/src \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HOME=/tmp/home \
    XDG_CACHE_HOME=/tmp/home/.cache \
    MPLCONFIGDIR=/tmp/home/.config/matplotlib \
    UV_CACHE_DIR=/tmp/home/.cache/uv \
    npm_config_cache=/tmp/home/.npm \
    SANDBOX_BASE_DIR=/workspace

USER 1000:1000
# 不能把工作目录设为 /workspace：容器启动时符号链接的目标还不存在，运行时会以 root 身份创建它
WORKDIR /var/lib/sandbox
EXPOSE 8888
# tini 作为 1 号进程回收孤儿进程：执行服务终止命令的进程组后，逃出进程组的子进程会被过继给它
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/sandbox-entrypoint"]

FROM runtime AS test
USER root
RUN --mount=type=cache,target=/root/.cache/uv \
    cd /opt/meridian/service && uv sync --frozen --group dev --group toolchain
COPY tests /opt/meridian/service/tests
USER 1000:1000
RUN mkdir -p "$HOME" && cd /opt/meridian/service && pytest -q -p no:cacheprovider -W error

# 默认构建目标是最终镜像；测试阶段只在 --target test 时执行
FROM runtime
