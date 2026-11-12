"""解析服务的 HTTP 接口（ADR-0051）。

- ``GET /healthz``：存活检查。
- ``POST /v1/parse``：``multipart/form-data`` 上传一个文件（字段 ``file``），返回 ``parser``、
  ``page_count``、``markdown`` 与 ``blocks``（类型、文字、表格的 Markdown、标题层级、页码、标题
  路径）。类型不支持 415，文件损坏 422，超过大小上限 413。

服务只在集群内网暴露，由网络策略限定只有 knowledge 能访问，不校验服务 JWT；日志不记录文件内容。
"""

import logging
import tempfile
import threading
import time
from pathlib import Path
from typing import Annotated, Any

from fastapi import FastAPI, File, HTTPException, UploadFile

from doc_parser.backends.base import Backend, ParseFailed, UnsupportedDocument
from doc_parser.settings import Settings, load_settings
from doc_parser.structure import to_markdown

log = logging.getLogger("doc_parser")

_CHUNK = 1024 * 1024


def create_backend(settings: Settings) -> Backend:
    """按配置创建后端。

    Args:
        settings: 服务配置。

    Returns:
        解析后端。

    Raises:
        ValueError: 后端名未知。
    """
    if settings.backend == "docling":
        from doc_parser.backends.docling_backend import DoclingBackend

        return DoclingBackend(settings.artifacts, settings.ocr_lang)
    if settings.backend == "mineru":
        from doc_parser.backends.mineru_backend import MineruBackend

        return MineruBackend(settings.mineru_command)
    raise ValueError(f"未知的解析后端：{settings.backend}")


def create_app(settings: Settings | None = None, backend: Backend | None = None) -> FastAPI:
    """创建应用。

    Args:
        settings: 配置；为空时从环境变量读取。
        backend: 解析后端；为空时按配置创建（测试注入替身）。

    Returns:
        FastAPI 应用。
    """
    config = settings or load_settings()
    parser = backend or create_backend(config)
    slots = threading.BoundedSemaphore(config.concurrency)
    app = FastAPI(title="AgentMeridian doc-parser", docs_url=None, redoc_url=None)

    @app.get("/healthz")
    def healthz() -> dict[str, str]:
        return {"status": "ok", "backend": parser.name}

    @app.post("/v1/parse")
    def parse(file: Annotated[UploadFile, File()]) -> dict[str, Any]:
        suffix = Path(file.filename or "").suffix.lower()
        with tempfile.TemporaryDirectory(prefix="doc-parser-") as work:
            path = Path(work) / ("upload" + suffix)
            size = _save(file, path, config.max_bytes)
            with slots:
                started = time.monotonic()
                try:
                    result = parser.parse(path)
                except UnsupportedDocument as e:
                    raise HTTPException(415, f"不支持的文件类型：{suffix or '无扩展名'}") from e
                except ParseFailed as e:
                    log.warning("解析失败 size=%d suffix=%s error=%s", size, suffix, e)
                    raise HTTPException(422, "文件无法解析") from e
            log.info(
                "解析完成 parser=%s pages=%d blocks=%d size=%d seconds=%.1f",
                result.parser,
                result.page_count,
                len(result.blocks),
                size,
                time.monotonic() - started,
            )
        return {
            "parser": result.parser,
            "page_count": result.page_count,
            "markdown": to_markdown(result.blocks),
            "blocks": [
                {
                    "type": b.type,
                    "text": b.text,
                    "markdown": b.markdown,
                    "level": b.level,
                    "page": b.page,
                    "heading_path": b.heading_path,
                }
                for b in result.blocks
            ],
        }

    return app


def _save(upload: UploadFile, target: Path, limit: int) -> int:
    size = 0
    with target.open("wb") as out:
        while chunk := upload.file.read(_CHUNK):
            size += len(chunk)
            if size > limit:
                raise HTTPException(413, f"文件超过大小上限 {limit} 字节")
            out.write(chunk)
    if size == 0:
        raise HTTPException(400, "文件为空")
    return size


def main() -> None:
    """命令行入口：``uvicorn`` 运行 :func:`create_app`。"""
    import uvicorn

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    uvicorn.run(create_app(), host="0.0.0.0", port=8000)


__all__ = ["create_app", "create_backend", "main"]
