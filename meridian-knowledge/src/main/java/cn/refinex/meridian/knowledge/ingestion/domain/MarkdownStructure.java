package cn.refinex.meridian.knowledge.ingestion.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 与纯文本的结构解析（ADR-0051），输出中间表示。
 *
 * <p>Markdown：ATX 标题（{@code #} 到 {@code ######}）、GFM 表格（连续的以 {@code |} 开头的行）、列表项（{@code -}、{@code *}、
 * {@code +}、{@code 1.}、{@code 1)}，续行并入）、围栏代码块（整体作为一个段落，保留换行），其余按空行分段，段内的换行合并。没有页码。
 * 纯文本只按空行分段。纯函数。
 */
public final class MarkdownStructure {

    /** Markdown 解析器的名字，记在文档的 {@code parser}。 */
    public static final String MARKDOWN = "markdown";

    /** 纯文本解析器的名字。 */
    public static final String TEXT = "text";

    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.+?)[ \\t#]*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^ {0,6}(?:[-*+]|\\d{1,9}[.)])[ \\t]+(.*)$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(```|~~~)");

    private MarkdownStructure() {}

    /**
     * 解析 Markdown。
     *
     * @param markdown 文本
     * @return 解析结果
     */
    public static ParsedDocument markdown(String markdown) {
        List<Block> blocks = new ArrayList<>();
        List<String> paragraph = new ArrayList<>();
        StringBuilder listItem = null;
        String[] lines = normalize(markdown).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher fence = FENCE.matcher(line);
            if (fence.find()) {
                listItem = closeList(blocks, listItem);
                closeParagraph(blocks, paragraph);
                StringBuilder code = new StringBuilder(line);
                int j = i + 1;
                while (j < lines.length && !lines[j].stripLeading().startsWith(fence.group(1))) {
                    code.append('\n').append(lines[j]);
                    j++;
                }
                if (j < lines.length) {
                    code.append('\n').append(lines[j]);
                }
                blocks.add(new Block.Paragraph(code.toString(), null));
                i = j;
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                listItem = closeList(blocks, listItem);
                closeParagraph(blocks, paragraph);
                blocks.add(new Block.Heading(
                        heading.group(1).length(), heading.group(2).strip(), null));
                continue;
            }
            if (line.stripLeading().startsWith("|")) {
                listItem = closeList(blocks, listItem);
                closeParagraph(blocks, paragraph);
                StringBuilder table = new StringBuilder(line.strip());
                while (i + 1 < lines.length && lines[i + 1].stripLeading().startsWith("|")) {
                    i++;
                    table.append('\n').append(lines[i].strip());
                }
                blocks.add(new Block.Table(table.toString(), null));
                continue;
            }
            Matcher item = LIST_ITEM.matcher(line);
            if (item.matches()) {
                listItem = closeList(blocks, listItem);
                closeParagraph(blocks, paragraph);
                listItem = new StringBuilder(item.group(1).strip());
                continue;
            }
            if (line.isBlank()) {
                listItem = closeList(blocks, listItem);
                closeParagraph(blocks, paragraph);
                continue;
            }
            if (listItem != null) {
                listItem.append(joiner(listItem, line.strip())).append(line.strip());
            } else {
                paragraph.add(line.strip());
            }
        }
        closeList(blocks, listItem);
        closeParagraph(blocks, paragraph);
        return new ParsedDocument(blocks, MARKDOWN);
    }

    /**
     * 解析纯文本：空行分段，段内的换行合并。
     *
     * @param text 文本
     * @return 解析结果
     */
    public static ParsedDocument plainText(String text) {
        List<Block> blocks = new ArrayList<>();
        List<String> paragraph = new ArrayList<>();
        for (String line : normalize(text).split("\n", -1)) {
            if (line.isBlank()) {
                closeParagraph(blocks, paragraph);
            } else {
                paragraph.add(line.strip());
            }
        }
        closeParagraph(blocks, paragraph);
        return new ParsedDocument(blocks, TEXT);
    }

    /**
     * 合并折行：汉字之间直接相连，英文单词之间加一个空格。
     *
     * @param lines 行
     * @return 合并后的文字
     */
    public static String joinLines(List<String> lines) {
        StringBuilder joined = new StringBuilder();
        for (String line : lines) {
            if (!joined.isEmpty()) {
                joined.append(joiner(joined, line));
            }
            joined.append(line);
        }
        return joined.toString();
    }

    private static String joiner(CharSequence previous, String next) {
        if (previous.isEmpty() || next.isEmpty()) {
            return "";
        }
        char last = previous.charAt(previous.length() - 1);
        char first = next.charAt(0);
        return last < 0x80 && first < 0x80 ? " " : "";
    }

    private static StringBuilder closeList(List<Block> blocks, StringBuilder item) {
        if (item != null && !item.isEmpty()) {
            blocks.add(new Block.ListItem(item.toString(), null));
        }
        return null;
    }

    private static void closeParagraph(List<Block> blocks, List<String> lines) {
        if (!lines.isEmpty()) {
            blocks.add(new Block.Paragraph(joinLines(lines), null));
            lines.clear();
        }
    }

    private static String normalize(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        return normalized.startsWith("﻿") ? normalized.substring(1) : normalized;
    }
}
