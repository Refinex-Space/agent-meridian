package cn.refinex.meridian.knowledge.ingestion.domain;

/**
 * PDF 的检查结果（ADR-0051）：决定用 PDFBox 抽取文字，还是交给 doc-parser 做版面解析。
 *
 * @param pages 页数
 * @param textChars 文字层中非空白字符的总数
 * @param gridPages 有框线网格（横竖线各至少 {@value #GRID_LINES} 条）的页数，视为表格
 */
public record PdfInspection(int pages, long textChars, int gridPages) {

    /** 平均每页的可见字符少于它时视为扫描件：正常的正文页有数百个字符，扫描件只有页码、水印之类的零星文字。 */
    public static final int SCANNED_CHARS_PER_PAGE = 50;

    /** 判定网格的最少横线与竖线条数（各自按位置去重）：至少两行两列的表格才有 3 条横线、3 条竖线。 */
    public static final int GRID_LINES = 3;

    /**
     * 是否像扫描件：文字层稀疏。
     *
     * @return 扫描件时为 true
     */
    public boolean scanned() {
        return pages > 0 && textChars < (long) pages * SCANNED_CHARS_PER_PAGE;
    }

    /**
     * 是否有表格。
     *
     * @return 至少一页有框线网格时为 true
     */
    public boolean hasTables() {
        return gridPages > 0;
    }

    /**
     * 是否交给 doc-parser：扫描件或有表格。
     *
     * @return 需要版面解析时为 true
     */
    public boolean needsLayoutParser() {
        return scanned() || hasTables();
    }
}
