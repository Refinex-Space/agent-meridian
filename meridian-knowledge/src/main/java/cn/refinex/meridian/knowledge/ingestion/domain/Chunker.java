package cn.refinex.meridian.knowledge.ingestion.domain;

import cn.refinex.meridian.common.text.TextTokens;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 切分器（架构设计「写入流程」第 3 步，ADR-0051）：按标题层级把文档分成节（同一标题路径下的连续块），节内按段落装箱到目标长度，
 * 相邻切片在节内重叠；表格整体成为一个切片，不与正文合并。
 *
 * <ul>
 *   <li>标题结束它所在层级及更低层级的节；标题文字不进入正文，记在切片的标题路径中。
 *   <li>段落与列表项先按句末标点拆成句子，单句超过目标长度减去重叠长度时按长度硬切；句子依次装进当前切片，放不下时结束当前切片，新切片以上一片
 *       末尾不超过重叠长度的句子开头（单句比重叠长度还长时取它的末尾一段）。所以除表格外，每个切片的估算长度不超过目标长度。
 *   <li>表格不超过 {@code maxTableTokens} 时整体保留；更长的按行拆成多片，每片重复表头与分隔行。
 * </ul>
 *
 * <p>长度用 {@link TextTokens#approximate} 估算。线程安全：不可变。
 */
public final class Chunker {

    private final Settings settings;

    /**
     * 切分参数。
     *
     * @param targetTokens 正文切片的长度上限，必须为正
     * @param overlapTokens 重叠长度，不小于 0 且小于目标长度
     * @param maxTableTokens 表格整体保留的上限，不小于目标长度
     */
    public record Settings(int targetTokens, int overlapTokens, int maxTableTokens) {

        /**
         * 校验参数。
         *
         * @throws IllegalArgumentException 取值不合法时
         */
        public Settings {
            if (targetTokens <= 0
                    || overlapTokens < 0
                    || overlapTokens >= targetTokens
                    || maxTableTokens < targetTokens) {
                throw new IllegalArgumentException(
                        "切分参数不合法：target=" + targetTokens + " overlap=" + overlapTokens + " maxTable=" + maxTableTokens);
            }
        }
    }

    /**
     * 创建切分器。
     *
     * @param settings 切分参数
     */
    public Chunker(Settings settings) {
        this.settings = settings;
    }

    /**
     * 切分一份文档。
     *
     * @param document 解析结果
     * @return 切片，{@code seq} 从 0 连续编号；没有正文与表格时为空列表
     */
    public List<ChunkDraft> split(ParsedDocument document) {
        Run run = new Run();
        for (Block block : document.blocks()) {
            switch (block) {
                case Block.Heading heading -> {
                    run.flush();
                    run.enter(heading);
                }
                case Block.Paragraph paragraph -> run.addText(paragraph.text(), paragraph.page());
                case Block.ListItem item ->
                    run.addText(item.text().isBlank() ? "" : "- " + item.text().strip(), item.page());
                case Block.Table table -> {
                    run.flush();
                    run.addTable(table);
                }
            }
        }
        run.flush();
        return run.chunks;
    }

    /** 一次切分的状态。 */
    private final class Run {

        private final List<ChunkDraft> chunks = new ArrayList<>();
        private final List<Heading> headings = new ArrayList<>();
        private final List<Piece> pieces = new ArrayList<>();

        void enter(Block.Heading heading) {
            String text = collapse(heading.text());
            if (text.isEmpty()) {
                return;
            }
            headings.removeIf(open -> open.level() >= heading.level());
            headings.add(new Heading(heading.level(), text));
        }

        void addText(String text, Integer page) {
            String normalized = text == null ? "" : text.strip();
            if (normalized.isEmpty()) {
                return;
            }
            boolean first = true;
            for (String sentence : Sentences.split(normalized)) {
                // 硬切的长度留出重叠：下一片以上一片的末尾开头时仍不超过目标长度
                for (String part : hardSplit(sentence, settings.targetTokens() - settings.overlapTokens())) {
                    pieces.add(new Piece(part, page, first, TextTokens.approximate(part)));
                    first = false;
                }
            }
        }

        void addTable(Block.Table table) {
            String markdown = table.markdown().strip();
            if (markdown.isEmpty()) {
                return;
            }
            if (TextTokens.approximate(markdown) <= settings.maxTableTokens()) {
                emit(ChunkKind.TABLE, markdown, table.page(), table.page());
                return;
            }
            String[] lines = markdown.split("\n");
            int headerLines = Math.min(2, lines.length);
            String header = String.join("\n", List.of(lines).subList(0, headerLines));
            StringBuilder rows = new StringBuilder();
            for (int i = headerLines; i < lines.length; i++) {
                String candidate = rows.isEmpty() ? lines[i] : rows + "\n" + lines[i];
                if (!rows.isEmpty() && TextTokens.approximate(header + "\n" + candidate) > settings.maxTableTokens()) {
                    emit(ChunkKind.TABLE, header + "\n" + rows, table.page(), table.page());
                    rows.setLength(0);
                    rows.append(lines[i]);
                } else {
                    rows.setLength(0);
                    rows.append(candidate);
                }
            }
            emit(ChunkKind.TABLE, rows.isEmpty() ? header : header + "\n" + rows, table.page(), table.page());
        }

        /** 把当前节的句子装箱成切片。 */
        void flush() {
            if (pieces.isEmpty()) {
                return;
            }
            List<Piece> current = new ArrayList<>();
            int tokens = 0;
            for (Piece piece : pieces) {
                if (!current.isEmpty() && tokens + piece.tokens() > settings.targetTokens()) {
                    emitText(current);
                    current = overlapTail(current, settings.targetTokens() - piece.tokens());
                    tokens = current.stream().mapToInt(Piece::tokens).sum();
                }
                current.add(piece);
                tokens += piece.tokens();
            }
            emitText(current);
            pieces.clear();
        }

        private void emitText(List<Piece> current) {
            StringBuilder content = new StringBuilder();
            Integer start = null;
            Integer end = null;
            for (Piece piece : current) {
                if (!content.isEmpty()) {
                    if (piece.paragraphStart()) {
                        content.append('\n');
                    } else if (needsSpace(
                            content.charAt(content.length() - 1), piece.text().charAt(0))) {
                        content.append(' ');
                    }
                }
                content.append(piece.text());
                if (piece.page() != null) {
                    start = start == null ? piece.page() : Math.min(start, piece.page());
                    end = end == null ? piece.page() : Math.max(end, piece.page());
                }
            }
            emit(ChunkKind.TEXT, content.toString(), start, end);
        }

        private void emit(ChunkKind kind, String content, Integer pageStart, Integer pageEnd) {
            List<String> path = headings.stream().map(Heading::text).toList();
            chunks.add(new ChunkDraft(
                    chunks.size(), kind, content, path, pageStart, pageEnd, TextTokens.approximate(content)));
        }

        /**
         * 上一片末尾用作重叠的句子：总长不超过重叠长度，也不超过 {@code budget}（保证加上下一句后不超过目标长度）。最后一句本身就超过时
         * 取它末尾的一段。
         */
        private List<Piece> overlapTail(List<Piece> previous, int budget) {
            int limit = Math.min(settings.overlapTokens(), budget);
            if (limit <= 0) {
                return new ArrayList<>();
            }
            List<Piece> tail = new ArrayList<>();
            int tokens = 0;
            for (int i = previous.size() - 1; i >= 0; i--) {
                Piece piece = previous.get(i);
                if (tokens + piece.tokens() > limit) {
                    break;
                }
                tail.add(piece);
                tokens += piece.tokens();
            }
            if (tail.isEmpty()) {
                Piece last = previous.getLast();
                String suffix = suffixWithin(last.text(), limit);
                if (!suffix.isEmpty()) {
                    tail.add(new Piece(suffix, last.page(), true, TextTokens.approximate(suffix)));
                }
                return tail;
            }
            Collections.reverse(tail);
            return tail;
        }
    }

    /** 拆成不超过 {@code limit} 的几段，切点落在码点边界上。 */
    static List<String> hardSplit(String text, int limit) {
        List<String> parts = new ArrayList<>();
        String rest = text;
        while (TextTokens.approximate(rest) > limit) {
            int cut = longestPrefixWithin(rest, limit);
            parts.add(rest.substring(0, cut));
            rest = rest.substring(cut).stripLeading();
        }
        if (!rest.isEmpty()) {
            parts.add(rest);
        }
        return parts;
    }

    /** 估算不超过 {@code limit} 的最长前缀的长度（字符数，至少一个码点）。 */
    private static int longestPrefixWithin(String text, int limit) {
        // 按码点数二分：估算长度随前缀单调不减
        int low = 1;
        int high = text.codePointCount(0, text.length());
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (TextTokens.approximate(text.substring(0, text.offsetByCodePoints(0, mid))) <= limit) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return text.offsetByCodePoints(0, low);
    }

    /** 估算不超过 {@code limit} 的最长后缀。 */
    private static String suffixWithin(String text, int limit) {
        int start = text.length();
        while (start > 0) {
            int previous = text.offsetByCodePoints(start, -1);
            if (TextTokens.approximate(text.substring(previous)) > limit) {
                break;
            }
            start = previous;
        }
        return text.substring(start).strip();
    }

    private static boolean needsSpace(char previous, char next) {
        return isAsciiWordChar(previous) && isAsciiWordChar(next);
    }

    private static boolean isAsciiWordChar(char c) {
        return c < 0x80 && Character.isLetterOrDigit(c) || c == ',' || c == '.' || c == ';' || c == ':';
    }

    private static String collapse(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ");
    }

    /** 当前打开的标题。 */
    private record Heading(int level, String text) {}

    /**
     * 装箱的单位：一句话，或硬切后的一段。
     *
     * @param text 文字
     * @param page 页码；可为空
     * @param paragraphStart 是否开始一个新段落（与前一句之间换行）
     * @param tokens 估算长度
     */
    private record Piece(String text, Integer page, boolean paragraphStart, int tokens) {}
}
