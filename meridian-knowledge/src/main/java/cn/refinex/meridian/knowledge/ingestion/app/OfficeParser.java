package cn.refinex.meridian.knowledge.ingestion.app;

import cn.refinex.meridian.knowledge.ingestion.domain.IngestionFailure;
import cn.refinex.meridian.knowledge.ingestion.domain.ParsedDocument;
import java.nio.file.Path;

/** Office、OpenDocument、RTF、HTML 的解析（实现是 Tika，Office 走 POI）。 */
public interface OfficeParser {

    /** 解析器的名字，记在文档的 {@code parser}。 */
    String NAME = "tika";

    /**
     * 解析。
     *
     * @param file 本机文件
     * @param filename 文件名，作为类型提示
     * @return 中间表示
     * @throws IngestionFailure 文件无法解析时（不可重试）
     */
    ParsedDocument parse(Path file, String filename);

    /**
     * 读取纯文本与 Markdown：按 UTF-8 解码，不是合法的 UTF-8 时按 GB18030（GBK、GB2312 的超集）。
     *
     * @param file 本机文件
     * @return 文字
     * @throws IngestionFailure 读取失败时（不可重试）
     */
    String readText(Path file);
}
