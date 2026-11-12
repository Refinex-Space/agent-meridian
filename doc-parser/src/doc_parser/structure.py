"""解析结果的结构：块的类型、中文编号推断标题层级、标题路径、文字清理与 Markdown 输出。

Docling 在 PDF 中给出的标题层级只有一层；中文文档的结构体现在编号上
（"第一章""第一节""一、""（一）""1."），这里按编号的种类排出层级，再为每个块算出标题路径
（ADR-0051）。
"""

import re
from dataclasses import dataclass, field

_NUMERAL = "一二三四五六七八九十百千零〇两"

# 编号的种类，按中文公文与制度文件的习惯从高到低排列
_KINDS: list[tuple[str, re.Pattern[str]]] = [
    ("part", re.compile(rf"^第[{_NUMERAL}\d]+[编部篇]")),
    ("chapter", re.compile(rf"^第[{_NUMERAL}\d]+章")),
    ("section", re.compile(rf"^第[{_NUMERAL}\d]+节")),
    ("cn_item", re.compile(rf"^[{_NUMERAL}]+[、.．]")),
    ("cn_paren", re.compile(rf"^[（(][{_NUMERAL}]+[）)]")),
    ("dotted", re.compile(r"^\d+(\.\d+)*[.．、\s]")),
    ("paren_digit", re.compile(r"^[（(]\d+[）)]")),
]

_CJK = r"[　-〿㐀-䶿一-鿿＀-￯]"
_CJK_GAP = re.compile(rf"(?<={_CJK})\s+(?={_CJK})")
_SPACES = re.compile(r"\s+")


@dataclass
class Block:
    """一个块。

    Attributes:
        type: ``heading``、``paragraph``、``list_item`` 或 ``table``。
        text: 文字；表格为单元格文字。
        page: 页码，从 1 开始；没有时为 None。
        level: 标题层级，从 1 开始；其他类型为 0。
        markdown: 表格的 Markdown；其他类型为 None。
        heading_path: 所在的标题路径（标题自己的路径含它本身）。
    """

    type: str
    text: str
    page: int | None = None
    level: int = 0
    markdown: str | None = None
    heading_path: list[str] = field(default_factory=list)


def clean_text(text: str) -> str:
    """合并空白，并去掉汉字之间因换行产生的空格。

    Args:
        text: 原始文字。

    Returns:
        清理后的文字。
    """
    return _CJK_GAP.sub("", _SPACES.sub(" ", text).strip())


def numbering_kind(text: str) -> tuple[str, int] | None:
    """识别标题的编号种类。

    Args:
        text: 标题文字。

    Returns:
        种类与它的排序（越小越高）；``1.2.3`` 形式按点的个数再下沉；没有编号时为 None。
    """
    for rank, (kind, pattern) in enumerate(_KINDS):
        match = pattern.match(text)
        if match:
            # "1." 与 "1.1" 的区别在编号内部的点，不算结尾的分隔符
            depth = match.group(0).rstrip(".．、 \t").count(".") if kind == "dotted" else 0
            return kind, rank * 10 + depth
    return None


def assign_levels(blocks: list[Block]) -> list[Block]:
    """按编号推断标题层级，并为每个块写上标题路径。

    没有编号、出现在第一个编号标题之前的标题（文档名）为第 1 层，此时编号标题整体下沉一层；
    之后出现的无编号标题（"附件"之类）与最高的编号标题同层。编号的层级按文档中实际出现的种类
    依次排列。

    Args:
        blocks: 解析得到的块，标题的 ``level`` 可以是任意值。

    Returns:
        同一列表，标题的 ``level`` 与全部块的 ``heading_path`` 已更新。
    """
    headings = [b for b in blocks if b.type == "heading"]
    ranks = sorted({k[1] for b in headings if (k := numbering_kind(b.text)) is not None})
    first_numbered = next((i for i, b in enumerate(headings) if numbering_kind(b.text)), None)
    offset = 1 if first_numbered is not None and first_numbered > 0 else 0
    for index, heading in enumerate(headings):
        kind = numbering_kind(heading.text)
        if kind is not None:
            heading.level = offset + 1 + ranks.index(kind[1])
        elif first_numbered is None or index < first_numbered:
            heading.level = 1
        else:
            heading.level = offset + 1
    path: list[tuple[int, str]] = []
    for block in blocks:
        if block.type == "heading":
            path = [entry for entry in path if entry[0] < block.level]
            path.append((block.level, block.text))
        block.heading_path = [text for _, text in path]
    return blocks


def to_markdown(blocks: list[Block]) -> str:
    """把块写成 Markdown（标题用 ``#``，列表项用 ``-``，表格原样）。

    Args:
        blocks: 块。

    Returns:
        Markdown 文本。
    """
    parts: list[str] = []
    for block in blocks:
        if block.type == "heading":
            parts.append("#" * min(max(block.level, 1), 6) + " " + block.text)
        elif block.type == "list_item":
            parts.append("- " + block.text)
        elif block.type == "table":
            parts.append(block.markdown or block.text)
        else:
            parts.append(block.text)
    return "\n\n".join(parts) + ("\n" if parts else "")
