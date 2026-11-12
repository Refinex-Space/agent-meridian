package cn.refinex.meridian.knowledge.ingestion.infra;

import cn.refinex.meridian.knowledge.ingestion.app.OfficeParser;
import cn.refinex.meridian.knowledge.ingestion.domain.Block;
import cn.refinex.meridian.knowledge.ingestion.domain.IngestionFailure;
import cn.refinex.meridian.knowledge.ingestion.domain.ParsedDocument;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * {@link OfficeParser} 的实现：Tika 把文档转成 XHTML 事件，本类把事件转成中间表示（ADR-0051）。{@code h1}–{@code h6} 是标题（Word 的
 * 标题样式由 POI 映射过来），{@code p class="title"} 视为最高层的标题，{@code li} 是列表项，{@code table} 转成 GFM 表格，其余
 * {@code p} 与块级文字是段落；页眉、页脚、批注与嵌入对象的文字不进入正文。没有页码。
 *
 * <p>线程安全：{@link AutoDetectParser} 可以共享，每次解析一个新的处理器。
 */
@Component
public class TikaOfficeParser implements OfficeParser {

    private static final Charset GB18030 = Charset.forName("GB18030");

    private final Parser parser = new AutoDetectParser();

    @Override
    public ParsedDocument parse(Path file, String filename) {
        XhtmlBlocks handler = new XhtmlBlocks();
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        try (TikaInputStream in = TikaInputStream.get(file, metadata)) {
            parser.parse(in, handler, metadata, new ParseContext());
        } catch (IOException | SAXException | TikaException e) {
            throw new IngestionFailure(
                    IngestionFailure.PARSE_FAILED, "文档无法解析：" + e.getClass().getSimpleName(), false, e);
        }
        return new ParsedDocument(handler.blocks, NAME);
    }

    @Override
    public String readText(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            try {
                return StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException notUtf8) {
                // 不是 UTF-8 的中文文本几乎都是 GBK 系：GB18030 是 GBK、GB2312 与 ASCII 的超集
                return new String(bytes, GB18030);
            }
        } catch (IOException e) {
            throw new IngestionFailure(
                    IngestionFailure.PARSE_FAILED, "文本无法读取：" + e.getClass().getSimpleName(), false, e);
        }
    }

    /** 把 Tika 的 XHTML 事件转成块。 */
    static final class XhtmlBlocks extends DefaultHandler {

        private final List<Block> blocks = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        /** 正在收集的块级元素：h1–h6、p、li；为空时文字丢弃（例如 head 中的 title）。 */
        private String capturing;

        private int captureDepth;
        private int headingLevel;
        /** 跳过的子树（页眉、页脚、批注、嵌入对象、head）的深度。 */
        private int skipDepth;

        private int depth;
        private Table table;
        private int tableDepth;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            depth++;
            String name = name(localName, qName);
            if (skipDepth > 0) {
                return;
            }
            String cls = attributes.getValue("class");
            if (name.equals("head") || name.equals("script") || name.equals("style") || isSkippedDiv(name, cls)) {
                skipDepth = depth;
                return;
            }
            if (name.equals("table")) {
                if (table == null) {
                    flush();
                    table = new Table();
                    tableDepth = depth;
                }
                return;
            }
            if (table != null) {
                switch (name) {
                    case "tr" -> table.startRow();
                    case "td", "th" -> table.startCell();
                    case "p", "br", "li", "div" -> table.space();
                    default -> {}
                }
                return;
            }
            if (capturing != null) {
                if (name.equals("br") || name.equals("p")) {
                    text.append(' ');
                }
                return;
            }
            if (name.length() == 2 && name.charAt(0) == 'h' && Character.isDigit(name.charAt(1))) {
                begin(name, Character.digit(name.charAt(1), 10));
            } else if (name.equals("p")
                    && cls != null
                    && cls.toLowerCase(Locale.ROOT).contains("title")) {
                begin("h", 0);
            } else if (name.equals("p") || name.equals("li") || name.equals("pre")) {
                begin(name, 0);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String name = name(localName, qName);
            try {
                if (skipDepth > 0) {
                    if (depth == skipDepth) {
                        skipDepth = 0;
                    }
                    return;
                }
                if (table != null) {
                    if (name.equals("table") && depth == tableDepth) {
                        String markdown = table.markdown();
                        if (!markdown.isEmpty()) {
                            blocks.add(new Block.Table(markdown, null));
                        }
                        table = null;
                    } else if (name.equals("td") || name.equals("th")) {
                        table.endCell();
                    }
                    return;
                }
                if (capturing != null && depth == captureDepth) {
                    flush();
                }
            } finally {
                depth--;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (skipDepth > 0) {
                return;
            }
            if (table != null) {
                table.append(ch, start, length);
            } else if (capturing != null) {
                text.append(ch, start, length);
            }
        }

        @Override
        public void endDocument() {
            flush();
        }

        private void begin(String element, int level) {
            capturing = element;
            captureDepth = depth;
            headingLevel = level;
            text.setLength(0);
        }

        private void flush() {
            if (capturing == null) {
                return;
            }
            String content = collapse(text);
            if (!content.isEmpty()) {
                if (capturing.equals("h") || (capturing.length() == 2 && capturing.charAt(0) == 'h')) {
                    blocks.add(new Block.Heading(headingLevel, content, null));
                } else if (capturing.equals("li")) {
                    blocks.add(new Block.ListItem(content, null));
                } else {
                    blocks.add(new Block.Paragraph(content, null));
                }
            }
            capturing = null;
            text.setLength(0);
        }

        private static boolean isSkippedDiv(String name, String cls) {
            if (!name.equals("div") || cls == null) {
                return false;
            }
            String lower = cls.toLowerCase(Locale.ROOT);
            return lower.contains("header")
                    || lower.contains("footer")
                    || lower.contains("comment")
                    || lower.contains("embedded")
                    || lower.contains("annotation");
        }

        private static String name(String localName, String qName) {
            return (localName == null || localName.isEmpty() ? qName : localName).toLowerCase(Locale.ROOT);
        }
    }

    /** 收集中的表格。 */
    private static final class Table {

        private final List<List<String>> rows = new ArrayList<>();
        private final StringBuilder cell = new StringBuilder();
        private boolean inCell;

        void startRow() {
            rows.add(new ArrayList<>());
        }

        void startCell() {
            if (rows.isEmpty()) {
                startRow();
            }
            inCell = true;
            cell.setLength(0);
        }

        void endCell() {
            if (inCell) {
                rows.getLast().add(collapse(cell).replace("|", "\\|"));
                inCell = false;
            }
        }

        void space() {
            if (inCell) {
                cell.append(' ');
            }
        }

        void append(char[] ch, int start, int length) {
            if (inCell) {
                cell.append(ch, start, length);
            }
        }

        String markdown() {
            List<List<String>> filled =
                    rows.stream().filter(row -> !row.isEmpty()).toList();
            if (filled.isEmpty() || filled.stream().allMatch(row -> row.stream().allMatch(String::isEmpty))) {
                return "";
            }
            int columns = filled.stream().mapToInt(List::size).max().orElse(0);
            StringBuilder markdown = new StringBuilder();
            for (int i = 0; i < filled.size(); i++) {
                markdown.append(row(filled.get(i), columns));
                if (i == 0) {
                    markdown.append('\n').append(row(java.util.Collections.nCopies(columns, "---"), columns));
                }
                if (i < filled.size() - 1) {
                    markdown.append('\n');
                }
            }
            return markdown.toString();
        }

        private static String row(List<String> cells, int columns) {
            StringBuilder line = new StringBuilder("|");
            for (int c = 0; c < columns; c++) {
                line.append(' ').append(c < cells.size() ? cells.get(c) : "").append(" |");
            }
            return line.toString();
        }
    }

    /** 合并空白：连续空白（含不换行空格）合并为一个空格。文档中有意的空格（"第一章 总则"）保留。 */
    static String collapse(CharSequence raw) {
        return raw.toString().replace('\u00a0', ' ').strip().replaceAll("\\s+", " ");
    }
}
