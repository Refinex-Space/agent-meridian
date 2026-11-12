package cn.refinex.meridian.knowledge.ingestion.app;

import cn.refinex.meridian.knowledge.document.domain.Document;
import cn.refinex.meridian.knowledge.document.domain.DocumentFormat;
import cn.refinex.meridian.knowledge.ingestion.domain.IngestionFailure;
import cn.refinex.meridian.knowledge.ingestion.domain.MarkdownStructure;
import cn.refinex.meridian.knowledge.ingestion.domain.ParsedDocument;
import cn.refinex.meridian.knowledge.ingestion.domain.PdfInspection;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 解析器的路由（架构设计「写入流程」第 2 步，ADR-0051）：按文档检测到的媒体类型选择解析器。Markdown 与纯文本用内置的结构解析；
 * Office、HTML、RTF、ODF 用 Tika；图片交给 doc-parser；PDF 先用 PDFBox 检查，扫描件与有框线表格的交给 doc-parser，其余用 PDFBox。
 */
@Service
public class DocumentParsing {

    private static final Logger log = LoggerFactory.getLogger(DocumentParsing.class);

    private final OfficeParser office;
    private final PdfParser pdf;
    private final LayoutParser layout;

    /**
     * 创建路由。
     *
     * @param office Office 解析器
     * @param pdf PDF 解析器
     * @param layout doc-parser
     */
    public DocumentParsing(OfficeParser office, PdfParser pdf, LayoutParser layout) {
        this.office = office;
        this.pdf = pdf;
        this.layout = layout;
    }

    /**
     * 解析文档。
     *
     * @param file 原文件的本机副本
     * @param document 文档
     * @return 中间表示
     * @throws IngestionFailure 类型不支持、文件无法解析（不可重试）或 doc-parser 暂时不可用（可重试）时
     */
    public ParsedDocument parse(Path file, Document document) {
        DocumentFormat format = DocumentFormat.of(document.mediaType())
                .orElseThrow(() ->
                        IngestionFailure.permanent(IngestionFailure.PARSE_FAILED, "不支持的文档类型：" + document.mediaType()));
        return switch (format) {
            case MARKDOWN -> MarkdownStructure.markdown(office.readText(file));
            case TEXT -> MarkdownStructure.plainText(office.readText(file));
            case OFFICE -> office.parse(file, document.filename());
            case IMAGE -> layout.parse(file, document.filename(), document.mediaType());
            case PDF -> parsePdf(file, document);
        };
    }

    private ParsedDocument parsePdf(Path file, Document document) {
        PdfInspection inspection = pdf.inspect(file);
        log.debug(
                "PDF 检查 documentId={} pages={} textChars={} gridPages={}",
                document.id(),
                inspection.pages(),
                inspection.textChars(),
                inspection.gridPages());
        if (inspection.needsLayoutParser()) {
            return layout.parse(file, document.filename(), document.mediaType());
        }
        return pdf.parse(file);
    }
}
