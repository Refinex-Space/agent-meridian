"""解析后端的接口与结果。"""

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from doc_parser.structure import Block

#: 支持的媒体类型：PDF 与常见图片。Office 文档由 knowledge 自己用 Tika 解析，不经过本服务。
SUPPORTED_SUFFIXES = {".pdf", ".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp", ".webp"}


class UnsupportedDocument(Exception):
    """文件类型不支持（415）。"""


class ParseFailed(Exception):
    """文件无法解析：损坏、加密等（422）。"""


@dataclass
class ParseResult:
    """一次解析的结果。

    Attributes:
        parser: 后端名，``docling`` 或 ``mineru``。
        page_count: 页数。
        blocks: 按阅读顺序排列、已推断层级与标题路径的块。
    """

    parser: str
    page_count: int
    blocks: list[Block]


class Backend(Protocol):
    """解析后端。"""

    name: str

    def parse(self, path: Path) -> ParseResult:
        """解析一个本机文件。

        Args:
            path: 文件，扩展名表明类型。

        Returns:
            解析结果。

        Raises:
            UnsupportedDocument: 类型不支持。
            ParseFailed: 文件无法解析。
        """
        ...
