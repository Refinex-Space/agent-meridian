package cn.refinex.meridian.knowledge.ingestion.app;

import cn.refinex.meridian.knowledge.ingestion.domain.IngestionFailure;
import cn.refinex.meridian.knowledge.ingestion.domain.ParsedDocument;
import cn.refinex.meridian.knowledge.ingestion.domain.PdfInspection;
import java.nio.file.Path;

/** PDF 的检查与文字抽取（实现是 PDFBox）。 */
public interface PdfParser {

    /** 解析器的名字，记在文档的 {@code parser}。 */
    String NAME = "pdfbox";

    /**
     * 检查文字层与框线，决定交给谁解析。
     *
     * @param file 本机文件
     * @return 检查结果
     * @throws IngestionFailure 文件无法打开时（不可重试）
     */
    PdfInspection inspect(Path file);

    /**
     * 抽取文字，按字号推断标题层级。
     *
     * @param file 本机文件
     * @return 中间表示，带页码
     * @throws IngestionFailure 文件无法解析时（不可重试）
     */
    ParsedDocument parse(Path file);
}
