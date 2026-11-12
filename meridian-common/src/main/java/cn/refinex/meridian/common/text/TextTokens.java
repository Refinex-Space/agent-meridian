package cn.refinex.meridian.common.text;

import java.util.List;

/**
 * 按字符数估算文本的 token，用于预算预扣、限流，以及供应商没有报告用量时的计量（架构设计「模型调用」，ADR-0032）：ASCII 每 3 个字符
 * 1 个 token，其余字符每个 1 个，误差方向偏多。内核的 {@code TokenEstimator}（消息、工具与媒体块）与 runtime 的 Embedding、rerank
 * 都以它估算文本，规则只有这一份（R1 Q-M1）。
 *
 * <p>纯函数，线程安全。
 */
public final class TextTokens {

    private static final int ASCII_CHARS_PER_TOKEN = 3;

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
