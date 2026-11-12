package cn.refinex.meridian.common.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 摘要的小写十六进制表示：API Key 的哈希、技能与产物的内容摘要、上传文件的摘要、先读后改的版本标记等共用（R1 Q-L1）。
 * 需要原始字节（常量时间比较）、截断或分段更新的场合直接用 {@link MessageDigest}。
 *
 * <p>纯函数，线程安全。
 */
public final class Sha256 {

    private static final int BUFFER_BYTES = 64 * 1024;

    private Sha256() {}

    /**
     * 字节的摘要。
     *
     * @param content 内容
     * @return 64 位小写十六进制
     */
    public static String hex(byte[] content) {
        return HexFormat.of().formatHex(digest().digest(content));
    }

    /**
     * 文本按 UTF-8 编码后的摘要。
     *
     * @param text 文本
     * @return 64 位小写十六进制
     */
    public static String hex(String text) {
        return hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 本机文件内容的摘要，分块读取，不整份读进内存。
     *
     * @param file 文件
     * @return 64 位小写十六进制
     * @throws UncheckedIOException 读取失败时
     */
    public static String hex(Path file) {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取文件失败：" + file, e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 规定必须提供的算法
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }
}
