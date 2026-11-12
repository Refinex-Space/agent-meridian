package cn.refinex.meridian.knowledge.ingestion.domain;

import java.util.List;

/**
 * 一份文档的解析结果。
 *
 * @param blocks 按阅读顺序排列的块
 * @param parser 解析器：{@code markdown}、{@code text}、{@code tika}、{@code pdfbox} 或 {@code doc-parser}
 */
public record ParsedDocument(List<Block> blocks, String parser) {

    /** 复制块列表。 */
    public ParsedDocument {
        blocks = List.copyOf(blocks);
    }
}
