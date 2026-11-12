package cn.refinex.meridian.knowledge.ingestion.infra;

import cn.refinex.meridian.knowledge.ingestion.app.PdfParser;
import cn.refinex.meridian.knowledge.ingestion.domain.Block;
import cn.refinex.meridian.knowledge.ingestion.domain.IngestionFailure;
import cn.refinex.meridian.knowledge.ingestion.domain.MarkdownStructure;
import cn.refinex.meridian.knowledge.ingestion.domain.ParsedDocument;
import cn.refinex.meridian.knowledge.ingestion.domain.PdfInspection;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

/**
 * {@link PdfParser} 的实现（PDFBox 3，ADR-0051）。
 *
 * <ul>
 *   <li>检查：文字层中非空白字符的数量（扫描件几乎没有），以及每页描边或细长填充的水平、垂直线段（框线表格至少各 3 条）。
 *   <li>抽取：按位置排序逐行读取，记下每行的字号与页码。正文字号取字符数最多的字号；比它大 15% 以上、不超过 80 个字符的行视为标题，
 *       字号从大到小依次为第 1、2…层。正文行按行距与行尾留白合并成段落：与上一行的行距超过正文常见行距的 1.3 倍，或上一行在
 *       右边距之前就结束，开始新段落。
 * </ul>
 *
 * <p>线程安全：无状态，每次解析打开一个新的文档。
 */
@Component
public class PdfBoxParser implements PdfParser {

    /** 标题行的字号至少是正文字号的倍数。 */
    static final float HEADING_SCALE = 1.15f;

    /** 标题行的字符上限：更长的大字号行视为正文（例如放大的引文）。 */
    static final int HEADING_MAX_CHARS = 80;

    /** 行尾在页面最右侧文字的这个比例之前结束，视为段落的最后一行。 */
    static final float SHORT_LINE = 0.85f;

    /** 行距超过正文常见行距的这个倍数，视为段落之间的间距。 */
    static final float PARAGRAPH_GAP = 1.3f;

    /** 线段的最短长度（点），更短的是字形的一部分或装饰。 */
    private static final float MIN_LINE_LENGTH = 10f;

    /** 线段视为水平或垂直的容差（点）。 */
    private static final float AXIS_TOLERANCE = 1f;

    /** 填充的矩形宽或高小于它时视为一条线。 */
    private static final float THIN_RECT = 3f;

    @Override
    public PdfInspection inspect(Path file) {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            long chars =
                    text.codePoints().filter(c -> !Character.isWhitespace(c)).count();
            int gridPages = 0;
            for (PDPage page : document.getPages()) {
                LineCollector lines = new LineCollector(page);
                lines.processPage(page);
                if (lines.isGrid()) {
                    gridPages++;
                }
            }
            return new PdfInspection(document.getNumberOfPages(), chars, gridPages);
        } catch (IOException e) {
            throw new IngestionFailure(IngestionFailure.PARSE_FAILED, "PDF 无法打开：" + e.getMessage(), false, e);
        }
    }

    @Override
    public ParsedDocument parse(Path file) {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            LineStripper stripper = new LineStripper();
            stripper.setSortByPosition(true);
            stripper.getText(document);
            return new ParsedDocument(structure(stripper.lines), NAME);
        } catch (IOException e) {
            throw new IngestionFailure(IngestionFailure.PARSE_FAILED, "PDF 无法解析：" + e.getMessage(), false, e);
        }
    }

    /** 把行转成块：标题按字号分层，正文按行距与行尾合并成段落。 */
    static List<Block> structure(List<Line> lines) {
        Map<Float, Integer> charsBySize = new HashMap<>();
        for (Line line : lines) {
            charsBySize.merge(line.size(), line.text().length(), Integer::sum);
        }
        float body = charsBySize.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0f);
        TreeSet<Float> headingSizes = new TreeSet<>();
        for (Line line : lines) {
            if (isHeading(line, body)) {
                headingSizes.add(line.size());
            }
        }
        List<Float> descending = new ArrayList<>(headingSizes.descendingSet());
        Map<Integer, Float> rightEdge = new HashMap<>();
        for (Line line : lines) {
            rightEdge.merge(line.page(), line.right(), Math::max);
        }
        float pitch = typicalPitch(lines, body);
        List<Block> blocks = new ArrayList<>();
        List<String> paragraph = new ArrayList<>();
        Integer paragraphPage = null;
        Line previous = null;
        for (Line line : lines) {
            if (isHeading(line, body)) {
                paragraphPage = closeParagraph(blocks, paragraph, paragraphPage);
                blocks.add(new Block.Heading(descending.indexOf(line.size()) + 1, line.text(), line.page()));
                previous = null;
                continue;
            }
            if (previous != null && startsParagraph(previous, line, rightEdge.get(previous.page()), pitch)) {
                paragraphPage = closeParagraph(blocks, paragraph, paragraphPage);
            }
            if (paragraph.isEmpty()) {
                paragraphPage = line.page();
            }
            paragraph.add(line.text());
            previous = line;
        }
        closeParagraph(blocks, paragraph, paragraphPage);
        return blocks;
    }

    private static boolean isHeading(Line line, float body) {
        return body > 0
                && line.size() >= body * HEADING_SCALE
                && line.text().codePointCount(0, line.text().length()) <= HEADING_MAX_CHARS;
    }

    /**
     * 新段落的判断：上一行在右边距之前就结束（段落的最后一行），或与上一行的行距明显大于正文的常见行距（段间距）。跨页时只看前者。
     */
    private static boolean startsParagraph(Line previous, Line line, float pageRight, float pitch) {
        if (previous.right() < pageRight * SHORT_LINE) {
            return true;
        }
        return line.page() == previous.page() && pitch > 0 && line.top() - previous.top() > pitch * PARAGRAPH_GAP;
    }

    /** 正文行之间最常见的行距（相邻两行行顶的距离的中位数）；正文少于两行时为 0。 */
    private static float typicalPitch(List<Line> lines, float body) {
        List<Float> deltas = new ArrayList<>();
        Line previous = null;
        for (Line line : lines) {
            if (line.size() != body) {
                previous = null;
                continue;
            }
            if (previous != null && previous.page() == line.page() && line.top() > previous.top()) {
                deltas.add(line.top() - previous.top());
            }
            previous = line;
        }
        if (deltas.isEmpty()) {
            return 0;
        }
        deltas.sort(Float::compare);
        return deltas.get(deltas.size() / 2);
    }

    private static Integer closeParagraph(List<Block> blocks, List<String> paragraph, Integer page) {
        if (!paragraph.isEmpty()) {
            String text = TikaOfficeParser.collapse(MarkdownStructure.joinLines(paragraph));
            if (!text.isEmpty()) {
                blocks.add(new Block.Paragraph(text, page));
            }
            paragraph.clear();
        }
        return null;
    }

    /**
     * 抽取的一行。
     *
     * @param text 文字
     * @param page 页码，从 1 开始
     * @param size 字号（点，取半点精度）
     * @param top 行顶的纵坐标（自上而下）
     * @param bottom 行底的纵坐标
     * @param right 行尾的横坐标
     */
    record Line(String text, int page, float size, float top, float bottom, float right) {}

    /** 按行收集文字与字号。 */
    private static final class LineStripper extends PDFTextStripper {

        private final List<Line> lines = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private final List<TextPosition> positions = new ArrayList<>();

        LineStripper() throws IOException {
            super();
            setLineSeparator("\n");
        }

        @Override
        public String getText(PDDocument doc) throws IOException {
            StringWriter ignored = new StringWriter();
            writeText(doc, ignored);
            endLine();
            return ignored.toString();
        }

        @Override
        protected void writeString(String string, List<TextPosition> textPositions) {
            text.append(string);
            positions.addAll(textPositions);
        }

        @Override
        protected void writeWordSeparator() {
            text.append(' ');
        }

        @Override
        protected void writeLineSeparator() {
            endLine();
        }

        @Override
        protected void writePageEnd() {
            endLine();
        }

        private void endLine() {
            String line = TikaOfficeParser.collapse(text);
            if (!line.isEmpty() && !positions.isEmpty()) {
                Map<Float, Integer> sizes = new HashMap<>();
                float top = Float.MAX_VALUE;
                float bottom = 0;
                float right = 0;
                for (TextPosition position : positions) {
                    float size = Math.round(position.getFontSizeInPt() * 2) / 2f;
                    sizes.merge(size, 1, Integer::sum);
                    top = Math.min(top, position.getYDirAdj() - position.getHeightDir());
                    bottom = Math.max(bottom, position.getYDirAdj());
                    right = Math.max(right, position.getXDirAdj() + position.getWidthDirAdj());
                }
                float size = sizes.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse(0f);
                lines.add(new Line(line, getCurrentPageNo(), size, top, bottom, right));
            }
            text.setLength(0);
            positions.clear();
        }
    }

    /** 收集一页中描边的线段与细长的填充矩形，判断是否有框线网格。 */
    private static final class LineCollector extends PDFGraphicsStreamEngine {

        private final List<float[]> path = new ArrayList<>();
        private final TreeSet<Integer> horizontal = new TreeSet<>();
        private final TreeSet<Integer> vertical = new TreeSet<>();
        private Point2D.Float current = new Point2D.Float();

        LineCollector(PDPage page) {
            super(page);
        }

        boolean isGrid() {
            return horizontal.size() >= PdfInspection.GRID_LINES && vertical.size() >= PdfInspection.GRID_LINES;
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            add(p0, p1);
            add(p1, p2);
            add(p2, p3);
            add(p3, p0);
        }

        @Override
        public void moveTo(float x, float y) {
            current = new Point2D.Float(x, y);
        }

        @Override
        public void lineTo(float x, float y) {
            Point2D.Float next = new Point2D.Float(x, y);
            add(current, next);
            current = next;
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            current = new Point2D.Float(x3, y3);
        }

        @Override
        public Point2D getCurrentPoint() {
            return current;
        }

        @Override
        public void closePath() {
            // 闭合段由矩形的四条边或显式的 lineTo 表示，这里不另算
        }

        @Override
        public void endPath() {
            path.clear();
        }

        @Override
        public void strokePath() {
            for (float[] segment : path) {
                classify(segment);
            }
            path.clear();
        }

        @Override
        public void fillPath(int windingRule) {
            // 细长的填充矩形常被用作表格线
            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (float[] segment : path) {
                minX = Math.min(minX, Math.min(segment[0], segment[2]));
                maxX = Math.max(maxX, Math.max(segment[0], segment[2]));
                minY = Math.min(minY, Math.min(segment[1], segment[3]));
                maxY = Math.max(maxY, Math.max(segment[1], segment[3]));
            }
            if (!path.isEmpty()) {
                if (maxY - minY < THIN_RECT && maxX - minX >= MIN_LINE_LENGTH) {
                    horizontal.add(Math.round((minY + maxY) / 4) * 2);
                } else if (maxX - minX < THIN_RECT && maxY - minY >= MIN_LINE_LENGTH) {
                    vertical.add(Math.round((minX + maxX) / 4) * 2);
                }
            }
            path.clear();
        }

        @Override
        public void fillAndStrokePath(int windingRule) {
            strokePath();
        }

        @Override
        public void clip(int windingRule) {
            // 裁剪路径不是可见的线
        }

        @Override
        public void drawImage(PDImage pdImage) {
            // 图片不影响框线判断
        }

        @Override
        public void shadingFill(org.apache.pdfbox.cos.COSName shadingName) {
            // 渐变填充不影响框线判断
        }

        private void add(Point2D from, Point2D to) {
            path.add(new float[] {(float) from.getX(), (float) from.getY(), (float) to.getX(), (float) to.getY()});
        }

        private void classify(float[] segment) {
            float dx = Math.abs(segment[2] - segment[0]);
            float dy = Math.abs(segment[3] - segment[1]);
            // 坐标按 2 点取整去重：同一条线的几段（例如相邻单元格的边）只算一次
            if (dy <= AXIS_TOLERANCE && dx >= MIN_LINE_LENGTH) {
                horizontal.add(Math.round(segment[1] / 2) * 2);
            } else if (dx <= AXIS_TOLERANCE && dy >= MIN_LINE_LENGTH) {
                vertical.add(Math.round(segment[0] / 2) * 2);
            }
        }
    }
}
