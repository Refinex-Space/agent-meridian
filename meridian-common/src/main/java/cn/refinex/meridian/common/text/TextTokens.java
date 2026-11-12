package cn.refinex.meridian.common.text;

import java.util.List;

/**
 * 估算文本的 token。两种口径，规则都只有这一份（R1 Q-M1）：
 *
 * <ul>
 *   <li>{@link #estimate}：按字符数，ASCII 每 3 个字符 1 个 token，其余字符每个 1 个，误差方向偏多。用于预算预扣、限流，以及供应商没有
 *       报告用量时的计量（架构设计「模型调用」，ADR-0032）；内核的 {@code TokenEstimator}（消息、工具与媒体块）与 runtime 的 Embedding、
 *       rerank 都以它估算文本。
 *   <li>{@link #approximate}：贴近 Qwen 系 BPE 分词的估算，用于知识库切分的长度控制（ADR-0051）。不用于预扣：它可能偏少。
 * </ul>
 *
 * <p>纯函数，线程安全。
 */
public final class TextTokens {

    private static final int ASCII_CHARS_PER_TOKEN = 3;

    /** {@link #approximate}：每 10 个汉字约 7 个 token（Qwen 系分词的中文正文平均值）。 */
    private static final int CJK_TOKENS_PER_TEN_CHARS = 7;

    /** {@link #approximate}：英文单词每 8 个字母计 1 个 token（常见单词是一个 token）。 */
    private static final int LETTERS_PER_WORD_TOKEN = 8;

    private TextTokens() {}

    /**
     * 估算一段文本。
     *
     * @param text 文本；可为空
     * @return token 估算，不小于 0
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int ascii = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (codePoint < 0x80) {
                ascii++;
            } else {
                other++;
            }
            i += Character.charCount(codePoint);
        }
        return other + (ascii + ASCII_CHARS_PER_TOKEN - 1) / ASCII_CHARS_PER_TOKEN;
    }

    /**
     * 按贴近 Qwen 系 BPE 分词的方式估算一段文本，给知识库切分控制切片长度（ADR-0051）。
     *
     * <p>规则：汉字、假名与谚文每个约 0.7 个 token（Qwen 的词表中常见的两字词是一个 token，中文正文平均约 1.4 个字一个 token）；
     * 连续的英文字母按单词计，8 个字母以内 1 个、更长的每 8 个加 1 个；数字逐位 1 个（Qwen 把数字拆成单个数字）；标点与其他符号每个
     * 1 个；空白不计（并入相邻的 token）。对中文正文，与 {@code qwen3.7-text-embedding} 报告的用量相差在 ±20% 以内；英文与代码偏差
     * 更大（罕见的长单词会被低估），切片的目标长度留有余量。
     *
     * @param text 文本；可为空
     * @return token 估算，不小于 0
     */
    public static int approximate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int tokens = 0;
        int letters = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            boolean letter = codePoint < 0x80 && Character.isLetter(codePoint);
            if (!letter && letters > 0) {
                tokens += (letters + LETTERS_PER_WORD_TOKEN - 1) / LETTERS_PER_WORD_TOKEN;
                letters = 0;
            }
            if (letter) {
                letters++;
            } else if (isIdeographic(codePoint)) {
                cjk++;
            } else if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                tokens++;
            }
        }
        tokens += (letters + LETTERS_PER_WORD_TOKEN - 1) / LETTERS_PER_WORD_TOKEN;
        return tokens + (cjk * CJK_TOKENS_PER_TEN_CHARS + 9) / 10;
    }

    private static boolean isIdeographic(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    /**
     * 估算多段文本之和。
     *
     * @param texts 文本
     * @return token 估算
     */
    public static int estimate(List<String> texts) {
        int total = 0;
        for (String text : texts) {
            total += estimate(text);
        }
        return total;
    }
}
