package cn.refinex.meridian.knowledge.ingestion.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 按句末标点把一段文字拆成句子：中文的 {@code 。！？；}、英文的 {@code !?;}，以及后面跟空白或位于结尾的 {@code .}；紧跟的右引号、右括号
 * 归入前一句。切分器以句子为装箱与重叠的单位（ADR-0051）。纯函数。
 */
final class Sentences {

    private static final String TERMINATORS = "。！？；!?;";
    private static final String CLOSERS = "”’」』）)\"'】》";

    private Sentences() {}

    /**
     * 拆分。
     *
     * @param text 一段文字
     * @return 句子，首尾空白已去掉，不含空句
     */
    static List<String> split(String text) {
        List<String> sentences = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            boolean end = TERMINATORS.indexOf(c) >= 0
                    || (c == '.' && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1))));
            i++;
            if (end) {
                while (i < text.length() && CLOSERS.indexOf(text.charAt(i)) >= 0) {
                    i++;
                }
                add(sentences, text.substring(start, i));
                start = i;
            }
        }
        add(sentences, text.substring(start));
        return sentences;
    }

    private static void add(List<String> sentences, String sentence) {
        String trimmed = sentence.strip();
        if (!trimmed.isEmpty()) {
            sentences.add(trimmed);
        }
    }
}
