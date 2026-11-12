"""标题层级的推断、标题路径与文字清理。"""

from doc_parser.structure import Block, assign_levels, clean_text, numbering_kind, to_markdown


def headings(*texts: str) -> list[Block]:
    return [Block("heading", text, 1, level=1) for text in texts]


def test_chinese_numbering_orders_levels() -> None:
    blocks = assign_levels(
        headings(
            "差旅费管理办法",
            "第一章 总则",
            "第一节 范围",
            "一、员工",
            "（一）正式员工",
            "第二章 审批",
            "附件",
        )
    )

    assert [b.level for b in blocks] == [1, 2, 3, 4, 5, 2, 2]
    assert blocks[4].heading_path == [
        "差旅费管理办法",
        "第一章 总则",
        "第一节 范围",
        "一、员工",
        "（一）正式员工",
    ]
    assert blocks[5].heading_path == ["差旅费管理办法", "第二章 审批"]


def test_levels_follow_the_kinds_present_and_dotted_numbers_nest() -> None:
    blocks = assign_levels(headings("一、适用范围", "1. 设备", "1.1 计算机", "二、流程", "无编号"))

    assert [b.level for b in blocks] == [1, 2, 3, 1, 1]


def test_paths_cover_body_blocks() -> None:
    blocks = assign_levels(
        [
            Block("paragraph", "前言", 1),
            Block("heading", "一、范围", 1),
            Block("paragraph", "正文", 1),
        ]
    )

    assert blocks[0].heading_path == []
    assert blocks[2].heading_path == ["一、范围"]


def test_numbering_kinds() -> None:
    assert numbering_kind("第十二条 规定") is None
    assert numbering_kind("第三章 费用") == ("chapter", 10)
    assert numbering_kind("（2）细则") == ("paren_digit", 60)
    assert numbering_kind("2.3.1 子项") == ("dotted", 52)


def test_clean_text_removes_spaces_between_chinese_only() -> None:
    assert clean_text(" 五百元以上 、使用期限\n超过一年 ") == "五百元以上、使用期限超过一年"
    assert clean_text("Docling  parses   PDF 文档") == "Docling parses PDF 文档"


def test_markdown_rendering() -> None:
    blocks = [
        Block("heading", "标题", level=2),
        Block("list_item", "条目"),
        Block("table", "a", markdown="| a |"),
        Block("paragraph", "正文"),
    ]

    assert to_markdown(blocks) == "## 标题\n\n- 条目\n\n| a |\n\n正文\n"
    assert to_markdown([]) == ""
