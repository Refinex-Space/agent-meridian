"""执行服务的 HTTP 接口，监听 8888 端口。

接口与字段按 AgentScope 2.0.4 ``agentscope-extensions-sandbox-kubernetes`` 的客户端
（``CommandExecutor``、``Filesystem``、``ExecutionResult``、``FileEntry``）逐字段对齐，
对照表见 ``README.md``。
请求路径中的 ``%2F`` 由 ASGI 服务器解码为 ``/``，所以路径参数可以带子目录。
"""

import asyncio
import logging
import os
import shutil
from typing import Annotated, Any

from fastapi import FastAPI, File, UploadFile
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field

from sandbox_runtime.executor import run_command
from sandbox_runtime.settings import Settings, load_settings

log = logging.getLogger("sandbox_runtime")


class ExecuteRequest(BaseModel):
    """``POST /execute`` 的请求体。

    Attributes:
        command: 交给 ``/bin/bash -c`` 的命令；扩展的 ``CommandExecutor`` 只发送这个字段。
        timeout_seconds: 可选，只能缩短服务端的超时上限。
    """

    command: str
    timeout_seconds: float | None = Field(default=None, gt=0, allow_inf_nan=False)


class ExecuteResponse(BaseModel):
    """``POST /execute`` 的响应体。

    对应扩展的 ``ExecutionResult``：``stdout``、``stderr``、``exit_code``；
    ``timed_out`` 为附加字段，扩展忽略它。
    """

    stdout: str
    stderr: str
    exit_code: int
    timed_out: bool = False


class PathOutsideBaseError(ValueError):
    """路径解析到工作区根目录之外。"""


def create_app(settings: Settings | None = None) -> FastAPI:
    """创建执行服务应用。

    Args:
        settings: 配置；为空时从环境变量读取。

    Returns:
        FastAPI 应用。
    """
    config = settings or load_settings()
    app = FastAPI(title="AgentMeridian Sandbox Runtime", version="0.1.0", docs_url=None)

    def resolve(relative: str) -> str:
        base = os.path.realpath(config.base_dir)
        full = os.path.realpath(os.path.join(base, relative.lstrip("/")))
        if os.path.commonpath([base, full]) != base:
            raise PathOutsideBaseError(relative)
        return full

    def denied() -> JSONResponse:
        return JSONResponse(status_code=403, content={"message": "Access denied"})

    @app.get("/")
    async def health() -> dict[str, str]:
        """健康检查，沙箱模板的就绪探针使用。"""
        return {"status": "ok"}

    @app.post("/execute")
    async def execute(request: ExecuteRequest) -> ExecuteResponse:
        """在工作区根目录执行一条命令。命令的非零退出码是业务结果，接口仍返回 200。"""
        timeout = config.exec_timeout_seconds
        if request.timeout_seconds is not None:
            timeout = min(timeout, request.timeout_seconds)
        try:
            outcome = await asyncio.to_thread(
                run_command,
                request.command,
                cwd=config.base_dir,
                timeout=timeout,
                output_limit=config.output_limit_bytes,
            )
        except OSError as e:
            return ExecuteResponse(stdout="", stderr=f"Failed to execute command: {e}", exit_code=1)
        return ExecuteResponse(
            stdout=outcome.stdout,
            stderr=outcome.stderr,
            exit_code=outcome.exit_code,
            timed_out=outcome.timed_out,
        )

    @app.post("/upload")
    async def upload(file: Annotated[UploadFile, File()]) -> JSONResponse:
        """把 multipart 字段 ``file`` 写到 ``filename`` 指定的相对路径，自动创建上级目录。"""
        try:
            target = resolve(file.filename or "")
        except PathOutsideBaseError:
            return denied()
        if target == os.path.realpath(config.base_dir) or os.path.isdir(target):
            return JSONResponse(status_code=400, content={"message": "Path is a directory"})
        try:
            await asyncio.to_thread(_save, file, target)
        except OSError as e:
            log.warning("upload failed: %s", e.strerror)
            return JSONResponse(status_code=500, content={"message": f"Upload failed: {e}"})
        return JSONResponse(
            status_code=200, content={"message": f"File '{file.filename}' uploaded successfully."}
        )

    @app.get("/download/{file_path:path}", response_model=None)
    async def download(file_path: str) -> FileResponse | JSONResponse:
        """返回文件的原始字节。"""
        try:
            target = resolve(file_path)
        except PathOutsideBaseError:
            return denied()
        if not os.path.isfile(target):
            return JSONResponse(status_code=404, content={"message": "File not found"})
        return FileResponse(
            target, media_type="application/octet-stream", filename=os.path.basename(target)
        )

    @app.get("/list/{file_path:path}")
    async def list_dir(file_path: str) -> JSONResponse:
        """列出目录的直接子项，字段对应扩展的 ``FileEntry``。"""
        try:
            target = resolve(file_path)
        except PathOutsideBaseError:
            return denied()
        if not os.path.isdir(target):
            return JSONResponse(status_code=404, content={"message": "Path is not a directory"})
        return JSONResponse(status_code=200, content=await asyncio.to_thread(_entries, target))

    @app.get("/exists/{file_path:path}")
    async def exists(file_path: str) -> JSONResponse:
        """判断路径是否存在，响应字段 ``exists`` 对应扩展的 ``ExistsResult``。"""
        try:
            target = resolve(file_path)
        except PathOutsideBaseError:
            return denied()
        return JSONResponse(
            status_code=200, content={"path": file_path, "exists": os.path.exists(target)}
        )

    return app


def _save(file: UploadFile, target: str) -> None:
    os.makedirs(os.path.dirname(target), exist_ok=True)
    file.file.seek(0)
    with open(target, "wb") as out:
        shutil.copyfileobj(file.file, out, 1024 * 1024)


def _entries(directory: str) -> list[dict[str, Any]]:
    entries: list[dict[str, Any]] = []
    with os.scandir(directory) as it:
        for entry in it:
            stats = entry.stat(follow_symlinks=False)
            entries.append(
                {
                    "name": entry.name,
                    "size": stats.st_size,
                    "type": "directory" if entry.is_dir(follow_symlinks=False) else "file",
                    "mod_time": stats.st_mtime,
                }
            )
    return entries


app = create_app()
