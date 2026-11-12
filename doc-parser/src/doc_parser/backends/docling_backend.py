"""默认后端：Docling（版面模型、TableFormer 快速模式、RapidOCR）。

模型在镜像构建时下载到 ``DOC_PARSER_ARTIFACTS`` 并固化，运行时 ``HF_HUB_OFFLINE=1`` 不联网；
只用版面、表格结构与 OCR 三类模型，公式、图片分类、VLM 不启用（ADR-0051）。
"""

import threading
from pathlib import Path

from docling.datamodel.base_models import ConversionStatus, InputFormat
from docling.datamodel.pipeline_options import (
    PdfPipelineOptions,
    RapidOcrOptions,
    TableFormerMode,
    TableStructureOptions,
)
from docling.document_converter import DocumentConverter, ImageFormatOption, PdfFormatOption
from docling.exceptions import ConversionError
from docling_core.types.doc.document import DoclingDocument
from docling_core.types.doc.items.node import DocItem
from docling_core.types.doc.items.table.table import TableItem
from docling_core.types.doc.items.text import ListItem, SectionHeaderItem, TextItem, TitleItem
from docling_core.types.doc.labels import DocItemLabel

from doc_parser.backends.base import (
    SUPPORTED_SUFFIXES,
    ParseFailed,
    ParseResult,
    UnsupportedDocument,
)
from doc_parser.structure import Block, assign_levels, clean_text

#: 作为正文保留的文字标签；页眉、页脚、目录等不进入正文
_BODY_LABELS = {
    DocItemLabel.TEXT,
    DocItemLabel.PARAGRAPH,
    DocItemLabel.CAPTION,
    DocItemLabel.FOOTNOTE,
    DocItemLabel.FORMULA,
    DocItemLabel.CODE,
    DocItemLabel.HANDWRITTEN_TEXT,
    DocItemLabel.REFERENCE,
}


class DoclingBackend:
    """Docling 后端。转换器在第一次解析时创建（加载模型约数秒），之后复用。"""

    name = "docling"

    def __init__(self, artifacts: Path | None, ocr_lang: str) -> None:
        """创建后端。

        Args:
            artifacts: 固化的模型目录；为空时 Docling 按需下载。
            ocr_lang: RapidOCR 的识别语言。
        """
        self._artifacts = artifacts
        self._ocr_lang = ocr_lang
        self._converter: DocumentConverter | None = None
        self._lock = threading.Lock()

    def _get_converter(self) -> DocumentConverter:
        with self._lock:
            if self._converter is None:
                options = PdfPipelineOptions(artifacts_path=self._artifacts)
                options.do_ocr = True
                options.ocr_options = RapidOcrOptions(lang=[self._ocr_lang])
                options.do_table_structure = True
                options.table_structure_options = TableStructureOptions(mode=TableFormerMode.FAST)
                options.do_picture_classification = False
                options.do_formula_enrichment = False
                options.do_code_enrichment = False
                self._converter = DocumentConverter(
                    allowed_formats=[InputFormat.PDF, InputFormat.IMAGE],
                    format_options={
                        InputFormat.PDF: PdfFormatOption(pipeline_options=options),
                        InputFormat.IMAGE: ImageFormatOption(pipeline_options=options),
                    },
                )
            return self._converter

    def parse(self, path: Path) -> ParseResult:
        """用 Docling 解析 PDF 或图片。

        Args:
            path: 文件。

        Returns:
            解析结果。

        Raises:
            UnsupportedDocument: 扩展名不是 PDF 或图片。
            ParseFailed: Docling 转换失败。
        """
        if path.suffix.lower() not in SUPPORTED_SUFFIXES:
            raise UnsupportedDocument(path.suffix)
        try:
            result = self._get_converter().convert(path, raises_on_error=True)
        except ConversionError as e:
            raise ParseFailed(str(e)) from e
        if result.status not in (ConversionStatus.SUCCESS, ConversionStatus.PARTIAL_SUCCESS):
            raise ParseFailed(f"转换状态 {result.status.value}")
        document = result.document
        return ParseResult(
            parser=self.name,
            page_count=len(document.pages),
            blocks=assign_levels(to_blocks(document)),
        )


def to_blocks(document: DoclingDocument) -> list[Block]:
    """把 Docling 文档按阅读顺序转成块。

    Args:
        document: Docling 的转换结果。

    Returns:
        块，标题层级尚未推断。
    """
    blocks: list[Block] = []
    for item, _ in document.iterate_items():
        if not isinstance(item, DocItem):
            continue
        page = item.prov[0].page_no if item.prov else None
        if isinstance(item, TableItem):
            markdown = item.export_to_markdown(doc=document).strip()
            if markdown:
                blocks.append(Block("table", clean_text(markdown), page, markdown=markdown))
        elif isinstance(item, TitleItem | SectionHeaderItem):
            text = clean_text(item.text)
            if text:
                blocks.append(Block("heading", text, page, level=getattr(item, "level", 1)))
        elif isinstance(item, ListItem):
            text = clean_text(item.text)
            if text:
                blocks.append(Block("list_item", text, page))
        elif isinstance(item, TextItem) and item.label in _BODY_LABELS:
            text = clean_text(item.text)
            if text:
                blocks.append(Block("paragraph", text, page))
    return blocks
