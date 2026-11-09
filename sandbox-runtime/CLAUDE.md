# sandbox-runtime

沙箱镜像与其中的执行服务（Python FastAPI，8888 端口）。runtime 经 AgentScope 2.0.4 的 Kubernetes 沙箱扩展调用它执行命令、读写工作区文件；接口对照表、预装清单、命令执行约束见 `README.md`，取舍见 ADR-0036。

## 命令

- 检查（`scripts/dev/verify.sh` 也会跑）：`cd sandbox-runtime && uv run ruff format --check . && uv run ruff check . && uv run mypy && uv run pytest -q -W error`
- 镜像：`docker build -t agent-meridian/sandbox-runtime:0.1.0 sandbox-runtime`；镜像内以 uid 1000 跑测试：`docker build --target test sandbox-runtime`
- 部署到本机 kind 集群（构建、`kind load`、helm、重建预热池中未认领的旧沙箱）：`scripts/dev/deploy-sandbox.sh meridian`

## 结构

- `src/sandbox_runtime/app.py`：健康检查 `/` 与五个业务接口、路径解析（越出 `SANDBOX_BASE_DIR` 的路径，含符号链接，返回 403）。
- `executor.py`：`run_command`，`/bin/bash -c`、新进程组、超时与输出上限、结束后整组 SIGKILL。
- `settings.py`：环境变量 `SANDBOX_BASE_DIR`、`SANDBOX_EXEC_TIMEOUT_SECONDS`、`SANDBOX_EXEC_OUTPUT_LIMIT_BYTES`（chart 设置）。
- `entrypoint.sh`：`ulimit -u $SANDBOX_MAX_PROCESSES`、创建 `/var/lib/sandbox/workspace` 与 `/tmp` 下的 HOME 和缓存、启动 uvicorn。
- `pyproject.toml`：依赖组 `dev`（测试与检查）、`toolchain`（只装进镜像的文档处理库）；版本以 `uv.lock` 为准。
- 仓库根的 `skills/builtin/`（内置技能的脚本，在沙箱里运行）也用本目录的 Ruff 配置检查：`uv run ruff check --config pyproject.toml ../skills`（`verify.sh` 已接入）。

## 不变量

- 接口与字段以 AgentScope 2.0.4 扩展的 `CommandExecutor`、`Filesystem`、`ExecutionResult`、`FileEntry` 为准，改接口先改 README 对照表并补接口测试（按扩展的 multipart 与百分号编码构造请求）。
- 镜像以 uid 1000 运行，根文件系统只读：可写的只有 `/var/lib/sandbox`（`/workspace` 是指向其下 `workspace/` 的符号链接）与 `/tmp`。新增工具时缓存目录必须落在 `/tmp`。
- 命令的非零退出码是业务结果，`/execute` 仍返回 200；只有请求本身无效才返回 4xx。
- 平台依赖镜像中的这些命令（T12a，ADR-0037）：`bash` 工具用 coreutils 的 `timeout` 落实超时；驱动用 `python3` 列 `outputs/`、用 `find`、`sort -z`、`xargs`、`sha256sum`、`cut` 算技能目录摘要，用 `tar` 写入。换基础镜像时保留它们。

## 易错点

- 不要把 `WORKDIR` 设为 `/workspace`：容器启动时链接目标还不存在，运行时会以 root 创建它，之后 uid 1000 写不进去。
- 不要直接在 emptyDir 挂载点上当工作区：挂载点属于 root，扩展恢复快照时 `tar -xf … -C /workspace` 会因设置 `.` 的时间与权限失败（退出码 2）。
- 扩展不传命令超时、每次 HTTP 尝试默认 60 秒：服务端上限（600 秒）要和驱动的 `perAttemptTimeoutSeconds`（命令超时加 60 秒）一起改。
- 命令结束后整组 SIGKILL：`cmd &` 留下的后台进程不会存活；要常驻用 `setsid`，它会被过继给 1 号进程 `tini`。
- `ulimit -u` 只对执行服务启动的命令生效，`kubectl exec` 进去的 shell 不受限；按沙箱计数依赖 gVisor。
- `docker run` 自测时要挂 `--tmpfs /var/lib/sandbox:uid=1000,gid=1000 --tmpfs /tmp`，否则只读根文件系统下入口脚本建不了工作区。
- Ruff 忽略 `RUF001`–`RUF003`（中文注释的全角标点）；`E501` 按字符计，中文 docstring 也要折行。
