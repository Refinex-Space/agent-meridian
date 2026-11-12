"""解析服务的配置，全部来自环境变量（镜像与 Compose 设置）。"""

import os
from dataclasses import dataclass
from pathlib import Path

DEFAULT_MAX_BYTES = 100 * 1024 * 1024


@dataclass(frozen=True)
class Settings:
    """解析服务配置。

    Attributes:
        backend: 解析后端，``docling``（默认）或 ``mineru``。
        artifacts: Docling 模型目录（镜像构建时固化）；为空时 Docling 按需从网络下载。
        max_bytes: 单个文件的大小上限，超过返回 413。
        ocr_lang: RapidOCR 的识别语言（PP-OCR 代码），默认 ``ch``（中英文）。
        concurrency: 同时进行的解析数；版面与 OCR 模型占内存，默认 1。
        mineru_command: MinerU 命令行的路径，``mineru`` 后端使用。
    """

    backend: str
    artifacts: Path | None
    max_bytes: int
    ocr_lang: str
    concurrency: int
    mineru_command: str


def load_settings() -> Settings:
    """从环境变量读取配置，非法的数值回退为默认值。

    Returns:
        当前进程的配置。
    """
    artifacts = os.environ.get("DOC_PARSER_ARTIFACTS", "").strip()
    return Settings(
        backend=os.environ.get("DOC_PARSER_BACKEND", "").strip() or "docling",
        artifacts=Path(artifacts) if artifacts else None,
        max_bytes=_positive_int("DOC_PARSER_MAX_BYTES", DEFAULT_MAX_BYTES),
        ocr_lang=os.environ.get("DOC_PARSER_OCR_LANG", "").strip() or "ch",
        concurrency=_positive_int("DOC_PARSER_CONCURRENCY", 1),
        mineru_command=os.environ.get("DOC_PARSER_MINERU_COMMAND", "").strip() or "mineru",
    )


def _positive_int(name: str, default: int) -> int:
    try:
        value = int(os.environ.get(name, ""))
    except ValueError:
        return default
    return value if value > 0 else default
