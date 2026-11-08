package cn.refinex.meridian.runtime.modelrouting.domain;

import java.util.List;

/**
 * Embedding 与 rerank 输入的 token 估算，用于限流与预扣，以及供应商没有报告用量时的计量。规则与内核的 {@code TokenEstimator} 相同
 * （ADR-0032）：ASCII 每 3 个字符 1 个 token，其余字符每个 1 个，误差方向偏多。内核的估算器在 {@code meridian-engine-agentscope} 中，
 * runtime 的业务包不能引用它（ArchUnit 规则 2）。
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
