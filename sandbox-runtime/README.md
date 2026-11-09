# sandbox-runtime

AgentMeridian 的沙箱镜像。每个需要执行代码的会话在一个由 gVisor 隔离的 Pod 中运行这个镜像，runtime 经 AgentScope 2.0.4 的 Kubernetes 沙箱扩展（`agentscope-extensions-sandbox-kubernetes`）调用其中的执行服务。

## 镜像

| 项 | 内容 |
|---|---|
| 基础镜像 | `debian:trixie-slim`（Debian 13） |
| 用户 | `sandbox`（uid 1000、gid 1000），镜像与 Pod 都以非 root 运行 |
| 工作区 | `/workspace`，指向 `/var/lib/sandbox/workspace` 的符号链接；`/var/lib/sandbox` 运行时为 emptyDir，`workspace/` 由入口脚本以 uid 1000 创建（emptyDir 挂载点属于 root，非 root 用户不能改它的时间与权限，恢复快照时 `tar -xf` 要改归档根目录 `.` 的这两项） |
| 1 号进程 | `tini`：执行服务终止命令的进程组后，逃出进程组的子进程过继给它并被回收 |
| 执行服务 | FastAPI + uvicorn，监听 8888 |
| 可写目录 | 只有 `/var/lib/sandbox`（即工作区）与 `/tmp`（根文件系统只读）；`HOME=/tmp/home`，pip、uv、npm、matplotlib 的缓存都在其下 |

构建与测试：

```bash
docker build -t agent-meridian/sandbox-runtime:0.1.0 sandbox-runtime          # 最终镜像
docker build --target test sandbox-runtime                                    # 以 uid 1000 在镜像内跑 pytest
docker buildx build --platform linux/amd64 sandbox-runtime                    # 另一架构（本机为 arm64）
kind load docker-image agent-meridian/sandbox-runtime:0.1.0 --name meridian   # 加载进本机集群
```

## 预装清单

这是架构设计待决问题 Q4「沙箱镜像预装的工具清单」的一期基线（R1 确认）：内置技能 `docx` 在其上运行，新技能需要的工具随镜像版本追加。版本取自 `uv.lock` 与 2026-11-08 的构建结果。

| 类别 | 内容 |
|---|---|
| Python | CPython 3.13（uv 管理，`/opt/meridian/python`），虚拟环境 `/opt/meridian/venv` 在 `PATH` 最前 |
| Python 库（`toolchain` 依赖组） | python-docx 1.2.0、openpyxl 3.1.5、python-pptx 1.0.2、pypdf 7.0.0、pandas 3.1.1（numpy 2.5.4）、matplotlib 3.11.2 |
| Node.js | Node.js 24（取自 `node:24-trixie-slim`）、npm、npx |
| 包管理 | uv 0.11.8、uvx |
| 命令行工具 | bash、coreutils、tar、gzip、xz、unzip、git、curl、jq、procps |
| 字体 | DejaVu（`fonts-dejavu-core`）、文泉驿微米黑（`fonts-wqy-microhei`，matplotlib 中文标签可用） |

Q4 待定项：LibreOffice（docx、pptx 转 PDF，约 500 MB）、Noto CJK 字体、Pandoc。执行服务与这些库在同一个虚拟环境，智能体的代码能导入 fastapi，这没有安全影响：服务与智能体本来就是同一个系统用户。

## 执行服务的接口约定对照

扩展源码取自 `agentscope-extensions-sandbox-kubernetes-2.0.4-sources.jar`（包 `io.agentscope.extensions.sandbox.kubernetes`，下表省略包前缀）；服务实现在 `src/sandbox_runtime/app.py`；接口测试在 `tests/test_files.py`（按扩展的 multipart 与路径编码写法构造请求）与 `tests/test_execute.py`。接口与字段同时兼容 kubernetes-sigs/agent-sandbox v1.0.10 的参考运行时 `examples/python-runtime-sandbox`（Apache-2.0）。

| 约定 | 扩展源码 | 服务实现 |
|---|---|---|
| 端口 8888 | `client/Constants.java:40` `DEFAULT_SERVER_PORT`、`KubernetesSandboxClientOptions.java:48` | `entrypoint.sh` `--port 8888` |
| `POST /execute`，JSON 请求体只有 `command` | `client/CommandExecutor.java:59-63` | `app.py` `execute`、`ExecuteRequest.command`；可选 `timeout_seconds` 只能缩短超时 |
| 响应 `stdout`、`stderr`、`exit_code`（缺省 -1，未知字段忽略） | `client/model/ExecutionResult.java:25-32` | `ExecuteResponse`；附加 `timed_out` |
| 非零退出码仍是 2xx，扩展据 `exit_code` 抛 `ExecException` | `KubernetesSandbox.java` `doExec` | 命令结果一律 200 |
| `/execute` 不重试（`maxRetries=1`），每次尝试的 HTTP 超时为 `perAttemptTimeoutSeconds`（默认 60 秒） | `client/internal/SandboxConnector.java:187-238` | 服务端超时上限 `SANDBOX_EXEC_TIMEOUT_SECONDS`（默认 600）；驱动把每次尝试的超时设为大于它 |
| 命令被包装为 `cd '<root>' && (\n<命令>\n)` | `KubernetesSandbox.java` `doExec` | `/bin/bash -c`，工作目录 `SANDBOX_BASE_DIR` |
| `POST /upload`，multipart 字段 `file`，`filename` 为相对路径（可含 `/`），内容类型 `application/octet-stream` | `client/Filesystem.java:64-75`、`:278-297` | `app.py` `upload`；`filename` 解析到工作区内 |
| 上传不要求服务端建目录，但 hydrate 上传 `.agentscope-tmp/…` 前没有 `mkdir` | `KubernetesSandbox.java` `uploadFile`、`hydrateViaFileApi` | 自动创建上级目录 |
| 上传遇到 500/502/503/504 重试，最多 3 次 | `client/Filesystem.java:69-74`、`SandboxConnector.java:50` | 写入失败返回 500 |
| `GET /download/{路径}`，路径整体百分号编码（`/` 为 `%2F`，空格为 `%20`），响应为原始字节 | `client/Filesystem.java:120-123`、`:300-302` | `app.py` `download`，`FileResponse` 流式返回；不存在 404 |
| `GET /list/{路径}` 返回数组，元素 `name`、`size`、`type`（`file`/`directory`）、`mod_time`（POSIX 秒，浮点） | `client/Filesystem.java:155-170`、`client/model/FileEntry.java:25-37` | `app.py` `list_dir`；不是目录时 404 |
| `GET /exists/{路径}` 返回 `{"exists": bool}` | `client/Filesystem.java:195-205`、`:311-315` | `app.py` `exists`；附加 `path` |
| 文件接口相对 `fileApiBaseDir`（默认 `/workspace`） | `KubernetesSandboxClientOptions.java:41`、`KubernetesSandbox.java` `toFileApiRelative` | `SANDBOX_BASE_DIR`（默认 `/workspace`）；越出它的路径（含符号链接）返回 403 |
| 快照与恢复：`tar -cf`、`tar -xf` 经 `/execute`，归档经 `/upload`、`/download` 传输 | `KubernetesSandbox.java` `persistViaFileApi`、`hydrateViaFileApi` | 镜像带 GNU tar；`/download` 流式返回大文件 |

## 命令执行的约束

| 项 | 行为 |
|---|---|
| 超时 | 默认 600 秒（`SANDBOX_EXEC_TIMEOUT_SECONDS`）。超时后整个进程组收到 SIGKILL，退出码 124，`timed_out=true`，stderr 末尾追加 `Command timed out after N seconds` |
| 输出上限 | stdout、stderr 各保留前 1 MiB（`SANDBOX_EXEC_OUTPUT_LIMIT_BYTES`），其余丢弃，末尾追加 `[output truncated: N bytes omitted]` |
| 进程组 | 每条命令一个新会话（进程组）；命令结束后向整个组发 SIGKILL，后台进程（`cmd &`）不会存活，也不会拖住响应。要常驻的进程须 `setsid` 脱离进程组 |
| 编码 | 输出按 UTF-8 解码，非法字节替换为 U+FFFD |
| 信号退出 | 被信号终止时退出码为 128 加信号值 |
