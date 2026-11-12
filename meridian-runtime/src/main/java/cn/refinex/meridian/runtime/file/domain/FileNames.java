package cn.refinex.meridian.runtime.file.domain;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/**
 * 文件名的规范化（ADR-0050）：文件名会成为工作区中的路径 {@code uploads/{file_id}/{filename}} 与下载的
 * {@code Content-Disposition}，所以只保留最后一段、去掉会被解释的字符，结果不含路径分隔符、不能是 {@code .} 或 {@code ..}。
 *
 * <ol>
 *   <li>取最后一个 {@code /} 或 {@code \} 之后的部分；
 *   <li>NFC 规范化；
 *   <li>去掉控制字符、格式字符（含双向文本覆盖符）与 {@code <>:"|?*}；
 *   <li>去掉首尾的空白与点；
 *   <li>按 UTF-8 截到 {@value #MAX_BYTES} 字节，扩展名（不超过 {@value #MAX_EXTENSION_BYTES} 字节）保留；
 *   <li>结果为空时为 {@value #FALLBACK}。
 * </ol>
 *
 * <p>纯函数，线程安全。
 */
public final class FileNames {

    /** 文件名的字节上限（常见文件系统的单段上限）。 */
    public static final int MAX_BYTES = 255;

    /** 截短时保留的扩展名的字节上限，更长的不当作扩展名。 */
    static final int MAX_EXTENSION_BYTES = 32;

    /** 规范化后为空时使用的名字。 */
    public static final String FALLBACK = "file";

    private static final String RESERVED = "<>:\"|?*";

    private FileNames() {}

    /**
     * 规范化文件名。
     *
     * @param raw 客户端给出的文件名；可为空
     * @return 规范化后的文件名，非空
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return FALLBACK;
        }
        String name = raw.substring(Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\')) + 1);
        name = Normalizer.normalize(name, Normalizer.Form.NFC);
        StringBuilder kept = new StringBuilder(name.length());
        name.codePoints()
                .filter(c -> !Character.isISOControl(c)
                        && Character.getType(c) != Character.FORMAT
                        && RESERVED.indexOf(c) < 0)
                .forEach(kept::appendCodePoint);
        name = trim(kept.toString());
        if (name.isEmpty()) {
            return FALLBACK;
        }
        return truncate(name);
    }

    private static String trim(String name) {
        int start = 0;
        int end = name.length();
        while (start < end && (Character.isWhitespace(name.charAt(start)) || name.charAt(start) == '.')) {
            start++;
        }
        while (end > start && (Character.isWhitespace(name.charAt(end - 1)) || name.charAt(end - 1) == '.')) {
            end--;
        }
        return name.substring(start, end);
    }

    private static String truncate(String name) {
        if (bytes(name) <= MAX_BYTES) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 ? name.substring(dot) : "";
        if (bytes(extension) > MAX_EXTENSION_BYTES) {
            extension = "";
        }
        String stem = extension.isEmpty() ? name : name.substring(0, dot);
        int budget = MAX_BYTES - bytes(extension);
        StringBuilder kept = new StringBuilder();
        int used = 0;
        for (int i = 0; i < stem.length(); ) {
            int codePoint = stem.codePointAt(i);
            int size = bytes(new String(Character.toChars(codePoint)));
            if (used + size > budget) {
                break;
            }
            kept.appendCodePoint(codePoint);
            used += size;
            i += Character.charCount(codePoint);
        }
        String result = trim(kept.toString()) + extension;
        return trim(result).isEmpty() ? FALLBACK : result;
    }

    private static int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
