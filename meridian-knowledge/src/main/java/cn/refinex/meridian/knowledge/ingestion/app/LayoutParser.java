package cn.refinex.meridian.knowledge.ingestion.app;

import cn.refinex.meridian.knowledge.ingestion.domain.IngestionFailure;
import cn.refinex.meridian.knowledge.ingestion.domain.ParsedDocument;
import java.nio.file.Path;

/** 版面解析：扫描件、复杂表格与图片交给 doc-parser（默认 Docling）。 */
public interface LayoutParser {

    /** 解析器的名字，记在文档的 {@code parser}。 */
    String NAME = "doc-parser";

    /**
     * 是否部署了 doc-parser。
     *
     * @return 配置了地址时为 true
     */
    boolean available();

    /**
     * 解析。
     *
     * @param file 本机文件
     * @param filename 文件名
     * @param mediaType 媒体类型
     * @return 中间表示，带页码与标题层级
     * @throws IngestionFailure doc-parser 暂时不可用（可重试）或拒绝了文件（不可重试）时
     */
    ParsedDocument parse(Path file, String filename, String mediaType);
}
