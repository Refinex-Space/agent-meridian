package cn.refinex.meridian.knowledge.document.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 支持的文档格式，按服务端检测的媒体类型归类，决定用哪个解析器（ADR-0051）。
 */
public enum DocumentFormat {
    /** Markdown：内置的结构解析。 */
    MARKDOWN,
    /** 纯文本、CSV：按空行分段。 */
    TEXT,
    /** PDF：PDFBox 检查后，扫描件与带框线表格的交给 doc-parser。 */
    PDF,
    /** Office、OpenDocument、RTF、HTML：Tika（Office 走 POI）。 */
    OFFICE,
    /** 图片：交给 doc-parser 做 OCR。 */
    IMAGE;

    private static final Set<String> MARKDOWN_TYPES = Set.of("text/markdown", "text/x-markdown", "text/x-web-markdown");
    private static final Set<String> TEXT_TYPES = Set.of("text/plain", "text/csv");
    private static final Set<String> OFFICE_TYPES = Set.of(
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/rtf",
            "text/rtf",
            "text/html",
            "application/xhtml+xml",
            "application/x-tika-ooxml",
            "application/x-tika-msoffice");
    private static final Set<String> IMAGE_TYPES =
            Set.of("image/png", "image/jpeg", "image/tiff", "image/bmp", "image/webp");

    /**
     * 按媒体类型归类。
     *
     * @param mediaType 检测到的媒体类型，可带参数（{@code ; charset=…}）
     * @return 格式；不支持时为空
     */
    public static Optional<DocumentFormat> of(String mediaType) {
        if (mediaType == null) {
            return Optional.empty();
        }
        String type = mediaType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (MARKDOWN_TYPES.contains(type)) {
            return Optional.of(MARKDOWN);
        }
        if (TEXT_TYPES.contains(type)) {
            return Optional.of(TEXT);
        }
        if (type.equals("application/pdf")) {
            return Optional.of(PDF);
        }
        if (OFFICE_TYPES.contains(type)
                || type.startsWith("application/vnd.openxmlformats-officedocument.")
                || type.startsWith("application/vnd.oasis.opendocument.")
                || type.startsWith("application/vnd.ms-word.")
                || type.startsWith("application/vnd.ms-excel.")
                || type.startsWith("application/vnd.ms-powerpoint.")) {
            return Optional.of(OFFICE);
        }
        if (IMAGE_TYPES.contains(type)) {
            return Optional.of(IMAGE);
        }
        return Optional.empty();
    }
}
