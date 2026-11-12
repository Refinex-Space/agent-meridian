"""后端：Docling 文档到块的转换、MinerU 的 content_list、配置与后端选择。"""

import os
from pathlib import Path

import pytest
from docling_core.types.doc import DocItemLabel, DoclingDocument, TableCell, TableData

from doc_parser.app import create_backend
from doc_parser.backends.base import UnsupportedDocument
from doc_parser.backends.docling_backend import DoclingBackend, to_blocks
from doc_parser.backends.mineru_backend import MineruBackend, content_list_blocks
from doc_parser.settings import DEFAULT_MAX_BYTES, Settings, load_settings
from tests.conftest import FIXTURES


def test_docling_items_become_blocks() -> None:
    document = DoclingDocument(name="t")
    document.add_title("办公设备采购与领用规定")
    document.add_heading("一、适用范围", level=1)
    document.add_text(label=DocItemLabel.TEXT, text="本规定 适用于 各部门。")
    document.add_text(label=DocItemLabel.PAGE_HEADER, text="页眉不进入正文")
    group = document.add_list_group()
    document.add_list_item("计算机", parent=group)
    cells = [
        TableCell(
            text="岗位",
            start_row_offset_idx=0,
            end_row_offset_idx=1,
            start_col_offset_idx=0,
            end_col_offset_idx=1,
            column_header=True,
        ),
        TableCell(
            text="研发",
            start_row_offset_idx=1,
            end_row_offset_idx=2,
            start_col_offset_idx=0,
            end_col_offset_idx=1,
        ),
    ]
    document.add_table(data=TableData(num_rows=2, num_cols=1, table_cells=cells))

    blocks = to_blocks(document)

    assert [b.type for b in blocks] == ["heading", "heading", "paragraph", "list_item", "table"]
    assert blocks[2].text == "本规定适用于各部门。"
    assert (
        blocks[4].markdown is not None
        and "岗位" in blocks[4].markdown
        and "研发" in blocks[4].markdown
    )


def test_mineru_content_list_becomes_blocks() -> None:
    items = [
        {"type": "text", "text": "第一章 总则", "text_level": 1, "page_idx": 0},
        {"type": "text", "text": "正文 内容", "page_idx": 0},
        {"type": "text", "text": " ", "page_idx": 0},
        {"type": "table", "table_body": "<table><tr><td>a</td></tr></table>", "page_idx": 1},
        {"type": "equation", "text": "$E=mc^2$", "page_idx": 1},
        {"type": "image", "img_path": "x.png", "page_idx": 1},
    ]

    blocks = content_list_blocks(items)

    assert [(b.type, b.page) for b in blocks] == [
        ("heading", 1),
        ("paragraph", 1),
        ("table", 2),
        ("paragraph", 2),
    ]
    assert blocks[1].text == "正文内容"


def test_unsupported_suffixes_are_rejected_before_parsing(tmp_path: Path) -> None:
    path = tmp_path / "a.docx"
    path.write_bytes(b"x")
    with pytest.raises(UnsupportedDocument):
        DoclingBackend(None, "ch").parse(path)
    with pytest.raises(UnsupportedDocument):
        MineruBackend("mineru").parse(path)


def test_settings_and_backend_selection(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("DOC_PARSER_MAX_BYTES", "oops")
    monkeypatch.setenv("DOC_PARSER_ARTIFACTS", "/models")
    monkeypatch.delenv("DOC_PARSER_BACKEND", raising=False)
    settings = load_settings()
    assert settings.max_bytes == DEFAULT_MAX_BYTES
    assert settings.artifacts == Path("/models")
    assert settings.backend == "docling"
    assert isinstance(create_backend(settings), DoclingBackend)
    mineru = Settings("mineru", None, 1, "ch", 1, "mineru")
    assert isinstance(create_backend(mineru), MineruBackend)
    with pytest.raises(ValueError):
        create_backend(Settings("other", None, 1, "ch", 1, "x"))


@pytest.mark.models
@pytest.mark.skipif(not os.environ.get("DOC_PARSER_ARTIFACTS"), reason="需要固化的 Docling 模型")
def test_scanned_pdf_is_recognized_with_ocr() -> None:
    settings = load_settings()
    backend = DoclingBackend(settings.artifacts, settings.ocr_lang)

    result = backend.parse(FIXTURES / "scanned-notice.pdf")
    text = "".join(b.text for b in result.blocks)

    assert result.page_count == 1
    assert "夏季作息时间" in text
    assert "十三点三十分" in text


@pytest.mark.models
@pytest.mark.skipif(not os.environ.get("DOC_PARSER_ARTIFACTS"), reason="需要固化的 Docling 模型")
def test_ruled_table_is_kept_as_one_table() -> None:
    settings = load_settings()
    result = DoclingBackend(settings.artifacts, settings.ocr_lang).parse(
        FIXTURES / "equipment-rules.pdf"
    )

    tables = [b for b in result.blocks if b.type == "table"]
    assert len(tables) == 1
    assert tables[0].markdown is not None and "研发工程师" in tables[0].markdown
    assert tables[0].heading_path == ["办公设备采购与领用规定", "三、配置标准"]
    assert {b.page for b in result.blocks} == {1, 2}
