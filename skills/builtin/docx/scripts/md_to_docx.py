"""把 Markdown 转成 Word 文档（AgentMeridian 内置技能 docx）。

在会话沙箱中运行，依赖沙箱镜像预装的 python-docx。支持标题、段落、粗体、斜体、行内代码、
无序与有序列表、围栏代码块、管道表格与分隔线；其余 Markdown 按普通段落输出。

用法：python3 md_to_docx.py <输入.md 或 -> <输出.docx> [--title 标题]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from docx import Document
from docx.enum.text import WD_BREAK
from docx.oxml.ns import qn
from docx.shared import Pt
from docx.text.paragraph import Paragraph

# 沙箱镜像预装的中文字体（fonts-wqy-microhei）
EAST_ASIAN_FONT = "WenQuanYi Micro Hei"
CODE_FONT = "DejaVu Sans Mono"

HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
BULLET = re.compile(r"^(\s*)[-*+]\s+(.*)$")
NUMBERED = re.compile(r"^(\s*)\d+[.)]\s+(.*)$")
FENCE = re.compile(r"^\s*(```|~~~)")
TABLE_SEPARATOR = re.compile(r"^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$")
RULE = re.compile(r"^\s*([-*_])(\s*\1){2,}\s*$")
INLINE = re.compile(r"(\*\*[^*]+\*\*|__[^_]+__|\*[^*]+\*|_[^_]+_|`[^`]+`)")


def add_inline(paragraph: Paragraph, text: str) -> None:
    """按粗体、斜体、行内代码拆分文本，逐段写入段落。"""
    for part in INLINE.split(text):
        if not part:
            continue
        if (part.startswith("**") and part.endswith("**")) or (
            part.startswith("__") and part.endswith("__")
        ):
            paragraph.add_run(part[2:-2]).bold = True
        elif part.startswith("`") and part.endswith("`"):
            run = paragraph.add_run(part[1:-1])
            run.font.name = CODE_FONT
        elif (part.startswith("*") and part.endswith("*")) or (
            part.startswith("_") and part.endswith("_")
        ):
            paragraph.add_run(part[1:-1]).italic = True
        else:
            paragraph.add_run(part)


def split_row(line: str) -> list[str]:
    """拆分管道表格的一行。"""
    cells = line.strip()
    if cells.startswith("|"):
        cells = cells[1:]
    if cells.endswith("|"):
        cells = cells[:-1]
    return [cell.strip() for cell in cells.split("|")]


def set_fonts(document: Document) -> None:
    """正文使用支持中文的字体。"""
    normal = document.styles["Normal"]
    normal.font.size = Pt(11)
    r_pr = normal.element.get_or_add_rPr()
    r_fonts = r_pr.find(qn("w:rFonts"))
    if r_fonts is None:
        r_fonts = r_pr.makeelement(qn("w:rFonts"), {})
        r_pr.append(r_fonts)
    r_fonts.set(qn("w:eastAsia"), EAST_ASIAN_FONT)


def convert(markdown: str, title: str | None) -> Document:
    """把 Markdown 文本转成文档对象。"""
    document = Document()
    set_fonts(document)
    if title:
        document.core_properties.title = title
    lines = markdown.splitlines()
    paragraph_lines: list[str] = []

    def flush_paragraph() -> None:
        if paragraph_lines:
            add_inline(document.add_paragraph(), " ".join(s.strip() for s in paragraph_lines))
            paragraph_lines.clear()

    i = 0
    while i < len(lines):
        line = lines[i]
        if FENCE.match(line):
            flush_paragraph()
            code: list[str] = []
            i += 1
            while i < len(lines) and not FENCE.match(lines[i]):
                code.append(lines[i])
                i += 1
            run = document.add_paragraph().add_run("\n".join(code))
            run.font.name = CODE_FONT
            run.font.size = Pt(9)
            i += 1
            continue
        if "|" in line and i + 1 < len(lines) and TABLE_SEPARATOR.match(lines[i + 1]):
            flush_paragraph()
            header = split_row(line)
            rows: list[list[str]] = []
            i += 2
            while i < len(lines) and "|" in lines[i] and lines[i].strip():
                rows.append(split_row(lines[i]))
                i += 1
            table = document.add_table(rows=1 + len(rows), cols=len(header))
            table.style = "Table Grid"
            for column, text in enumerate(header):
                cell = table.rows[0].cells[column].paragraphs[0]
                cell.add_run(text).bold = True
            for row_index, row in enumerate(rows, start=1):
                for column in range(len(header)):
                    text = row[column] if column < len(row) else ""
                    add_inline(table.rows[row_index].cells[column].paragraphs[0], text)
            continue
        heading = HEADING.match(line)
        bullet = BULLET.match(line)
        numbered = NUMBERED.match(line)
        if heading:
            flush_paragraph()
            add_inline(document.add_heading(level=len(heading.group(1))), heading.group(2))
        elif RULE.match(line):
            flush_paragraph()
            document.add_paragraph().add_run().add_break(WD_BREAK.LINE)
        elif bullet:
            flush_paragraph()
            add_inline(document.add_paragraph(style="List Bullet"), bullet.group(2))
        elif numbered:
            flush_paragraph()
            add_inline(document.add_paragraph(style="List Number"), numbered.group(2))
        elif not line.strip():
            flush_paragraph()
        else:
            paragraph_lines.append(line)
        i += 1
    flush_paragraph()
    return document


def main(argv: list[str]) -> int:
    """命令行入口：读取 Markdown，写出 docx，打印结果摘要。"""
    parser = argparse.ArgumentParser(description="Convert Markdown to a .docx file.")
    parser.add_argument("source", help="Markdown file, or - to read standard input")
    parser.add_argument("output", help="output .docx path, for example outputs/report.docx")
    parser.add_argument("--title", help="document title property")
    args = parser.parse_args(argv)
    if not args.output.endswith(".docx"):
        print("error: the output file name must end with .docx", file=sys.stderr)
        return 2
    try:
        markdown = (
            sys.stdin.read()
            if args.source == "-"
            else Path(args.source).read_text(encoding="utf-8")
        )
    except OSError as error:
        print(f"error: cannot read {args.source}: {error}", file=sys.stderr)
        return 1
    document = convert(markdown, args.title)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    document.save(str(output))
    print(f"wrote {output} ({len(document.paragraphs)} paragraphs, {len(document.tables)} tables)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
