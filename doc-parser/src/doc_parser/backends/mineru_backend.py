"""可选后端：MinerU。

MinerU 体积大（多个模型数 GB），许可证为它自己的开源许可，使用前需要核对；默认镜像不安装，
构建参数 ``WITH_MINERU=true`` 时才装进镜像。本后端调用 MinerU 的命令行
``mineru -p <文件> -o <目录> -b pipeline``，读取它输出的 ``*_content_list.json``
（每项带 ``type``、``text``、``text_level``、``page_idx``）。
"""

import json
import subprocess
import tempfile
from pathlib import Path
from typing import Any

from doc_parser.backends.base import (
    SUPPORTED_SUFFIXES,
    ParseFailed,
    ParseResult,
    UnsupportedDocument,
)
from doc_parser.structure import Block, assign_levels, clean_text

#: 单个文件的解析时间上限（秒）
TIMEOUT_SECONDS = 1800


class MineruBackend:
    """MinerU 后端。"""

    name = "mineru"

    def __init__(self, command: str) -> None:
        """创建后端。

        Args:
            command: ``mineru`` 命令的路径。
        """
        self._command = command

    def parse(self, path: Path) -> ParseResult:
        """用 MinerU 的命令行解析 PDF 或图片。

        Args:
            path: 文件。

        Returns:
            解析结果。

        Raises:
            UnsupportedDocument: 扩展名不是 PDF 或图片。
            ParseFailed: 命令失败或没有输出。
        """
        if path.suffix.lower() not in SUPPORTED_SUFFIXES:
            raise UnsupportedDocument(path.suffix)
        with tempfile.TemporaryDirectory(prefix="mineru-") as out:
            try:
                subprocess.run(
                    [self._command, "-p", str(path), "-o", out, "-b", "pipeline"],
                    check=True,
                    capture_output=True,
                    timeout=TIMEOUT_SECONDS,
                )
            except (OSError, subprocess.SubprocessError) as e:
                raise ParseFailed(f"MinerU 执行失败：{type(e).__name__}") from e
            outputs = sorted(Path(out).rglob("*_content_list.json"))
            if not outputs:
                raise ParseFailed("MinerU 没有输出 content_list.json")
            items = json.loads(outputs[0].read_text(encoding="utf-8"))
        blocks = content_list_blocks(items)
        pages = max((b.page or 0 for b in blocks), default=0)
        return ParseResult(parser=self.name, page_count=pages, blocks=assign_levels(blocks))


def content_list_blocks(items: list[dict[str, Any]]) -> list[Block]:
    """把 MinerU 的 content_list 转成块。

    Args:
        items: ``*_content_list.json`` 的内容。

    Returns:
        块，标题层级尚未推断。
    """
    blocks: list[Block] = []
    for item in items:
        page = int(item["page_idx"]) + 1 if "page_idx" in item else None
        kind = item.get("type")
        if kind == "text":
            text = clean_text(str(item.get("text", "")))
            if not text:
                continue
            level = int(item.get("text_level", 0) or 0)
            blocks.append(Block("heading" if level else "paragraph", text, page, level=level))
        elif kind == "table":
            # MinerU 的表格是 HTML，原样作为表格切片的内容
            body = str(item.get("table_body", "")).strip()
            if body:
                blocks.append(Block("table", clean_text(body), page, markdown=body))
        elif kind == "equation":
            text = str(item.get("text", "")).strip()
            if text:
                blocks.append(Block("paragraph", text, page))
    return blocks
