package cn.refinex.meridian.knowledge.ingestion.domain;

/**
 * 解析结果的中间表示中的一个块（ADR-0051）：所有解析器（Markdown、纯文本、Tika、PDFBox、doc-parser）都输出块的序列，切分器只依赖它。
 * 页码从 1 开始；Office、Markdown 等没有分页的格式为空。
 */
public sealed interface Block permits Block.Heading, Block.Paragraph, Block.ListItem, Block.Table {

    /**
     * 块所在的页。
     *
     * @return 页码；没有分页时为空
     */
    Integer page();

    /**
     * 标题。
     *
     * @param level 层级，1 最高；切分时层级相同或更高的标题结束前一个节
     * @param text 标题文字（含编号，例如"第三章 费用标准"）
     * @param page 页码；可为空
     */
    record Heading(int level, String text, Integer page) implements Block {}

    /**
     * 段落。
     *
     * @param text 文字，段内的换行已合并
     * @param page 页码；可为空
     */
    record Paragraph(String text, Integer page) implements Block {}

    /**
     * 列表项。
     *
     * @param text 文字，不含列表符号
     * @param page 页码；可为空
     */
    record ListItem(String text, Integer page) implements Block {}

    /**
     * 表格，整体作为一个切片（过大时按行拆分）。
     *
     * @param markdown GFM 表格：第一行表头、第二行分隔行，之后每行一条记录
     * @param page 起始页码；可为空
     */
    record Table(String markdown, Integer page) implements Block {}
}
