package cn.refinex.meridian.testsupport.model;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * 脚本化模型服务的向量与重排算法：确定、无外部依赖。
 *
 * <ul>
 *   <li>Embedding：以文本 SHA-256 的前 8 字节为种子生成 [-1, 1) 的伪随机分量，再归一化为单位向量。同一文本总得到同一向量。
 *   <li>rerank：关键词重叠率。ASCII 按单词（字母数字串，忽略大小写），其余字符按单字切分；得分是查询的词中出现在候选文本里的比例。
 * </ul>
 *
 * <p>token 估算与平台相同：ASCII 每 3 个字符 1 个，其余字符每个 1 个。线程安全：无状态。
 */
public final class ScriptedVectors {

    /** 请求没有指定维度时的向量维度，与 DashScope {@code qwen3.7-text-embedding} 相同。 */
    public static final int DEFAULT_DIMENSIONS = 1024;

    private ScriptedVectors() {}

    /**
     * 文本的确定向量。
     *
     * @param text 文本
     * @param dimensions 维度，大于 0
     * @return 单位向量
     */
    public static float[] embed(String text, int dimensions) {
        long seed = ByteBuffer.wrap(sha256(text)).getLong();
        SplittableRandom random = new SplittableRandom(seed);
        float[] vector = new float[dimensions];
        double norm = 0;
        for (int i = 0; i < dimensions; i++) {
            double value = random.nextDouble() * 2 - 1;
            vector[i] = (float) value;
            norm += value * value;
        }
        double length = Math.sqrt(norm);
        for (int i = 0; i < dimensions; i++) {
            vector[i] = (float) (vector[i] / length);
        }
        return vector;
    }

    /**
     * 查询与候选文本的关键词重叠率。
     *
     * @param query 查询
     * @param document 候选文本
     * @return [0, 1] 内的得分；查询没有词时为 0
     */
    public static double overlap(String query, String document) {
        Set<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) {
            return 0;
        }
        Set<String> documentTerms = terms(document);
        long hits = queryTerms.stream().filter(documentTerms::contains).count();
        return (double) hits / queryTerms.size();
    }

    /**
     * 估算 token。
     *
     * @param text 文本；可为空
     * @return token 数
     */
    public static int tokens(String text) {
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
        return other + (ascii + 2) / 3;
    }

    private static Set<String> terms(String text) {
        Set<String> terms = new HashSet<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (codePoint < 0x80 && Character.isLetterOrDigit(codePoint)) {
                word.appendCodePoint(Character.toLowerCase(codePoint));
                continue;
            }
            if (!word.isEmpty()) {
                terms.add(word.toString());
                word.setLength(0);
            }
            if (codePoint >= 0x80 && Character.isLetterOrDigit(codePoint)) {
                terms.add(new String(Character.toChars(codePoint)).toLowerCase(Locale.ROOT));
            }
        }
        if (!word.isEmpty()) {
            terms.add(word.toString());
        }
        return terms;
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 规定必须提供的算法
            throw new IllegalStateException(e);
        }
    }
}
