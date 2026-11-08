package cn.refinex.meridian.engine.agentscope.sandbox;

import cn.refinex.meridian.engine.spi.driver.SandboxDriver;
import cn.refinex.meridian.engine.spi.spec.SkillDefinition;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/**
 * 技能资源在沙箱中的投影：要写入的文件、内容摘要与 tar 归档（架构设计「会话沙箱」"技能资源"）。
 *
 * <p>摘要的算法与沙箱内的 {@link #DIGEST_COMMAND} 逐字节一致：在 {@value SandboxDriver#SKILLS_DIR} 目录下，按 C 语言区域的字节序
 * 排列 {@code ./相对路径}，每个文件一行 {@code sha256  ./相对路径}（{@code sha256sum} 的输出格式），对全部行再取 SHA-256。
 * 没有技能时期望目录不存在，摘要为空串。只统计普通文件：被换成符号链接的文件会从清单中消失，同样判为不一致。
 *
 * <p>归档用 commons-compress（AgentScope 2.0.4 的 {@code agentscope-core} 引入，harness 的 {@code SandboxBackedFilesystem}
 * 同样用它打包上传）。
 */
final class SkillProjection {

    /**
     * 在沙箱工作区根执行，输出技能目录的摘要；目录不存在时没有输出。
     */
    static final String DIGEST_COMMAND =
            "if [ -d " + SandboxDriver.SKILLS_DIR + " ]; then cd " + SandboxDriver.SKILLS_DIR
                    + " && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 -r sha256sum | sha256sum | cut -d' ' -f1; fi";

    /** 写入前清空技能目录。 */
    static final String CLEAR_COMMAND = "rm -rf " + SandboxDriver.SKILLS_DIR;

    private SkillProjection() {}

    /**
     * 要投影的文件：每个技能除 {@code SKILL.md} 之外的全部文件，路径相对技能目录（{@code {技能名}/{文件}}）。
     *
     * @param skills 技能
     * @return 路径 → 内容（UTF-8），按路径的字节序排列
     */
    static Map<String, byte[]> files(List<SkillDefinition> skills) {
        List<Map.Entry<String, byte[]>> entries = new ArrayList<>();
        for (SkillDefinition skill : skills) {
            skill.files().forEach((path, content) -> {
                if (!path.equals(SkillDefinition.SKILL_FILE)) {
                    entries.add(Map.entry(skill.name() + "/" + path, content.getBytes(StandardCharsets.UTF_8)));
                }
            });
        }
        entries.sort((a, b) -> Arrays.compareUnsigned(
                ("./" + a.getKey()).getBytes(StandardCharsets.UTF_8),
                ("./" + b.getKey()).getBytes(StandardCharsets.UTF_8)));
        Map<String, byte[]> files = new LinkedHashMap<>();
        entries.forEach(e -> files.put(e.getKey(), e.getValue()));
        return files;
    }

    /**
     * 文件的期望摘要，与 {@link #DIGEST_COMMAND} 的输出比较。
     *
     * @param files {@link #files} 的结果
     * @return 小写十六进制摘要；没有文件时为空串
     */
    static String digest(Map<String, byte[]> files) {
        if (files.isEmpty()) {
            return "";
        }
        StringBuilder lines = new StringBuilder();
        files.forEach((path, content) ->
                lines.append(sha256(content)).append("  ./").append(path).append('\n'));
        return sha256(lines.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 把文件打成 tar，条目路径为 {@code SKILLS_DIR/相对路径}，在工作区根展开。不含目录条目（{@code tar -x} 自动建目录），
     * 也就没有归档根 {@code .}：工作区挂载点上设置 {@code .} 的时间与权限会失败（ADR-0036）。
     *
     * @param files {@link #files} 的结果
     * @return tar 归档
     */
    static byte[] archive(Map<String, byte[]> files) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                TarArchiveEntry entry = new TarArchiveEntry(SandboxDriver.SKILLS_DIR + "/" + file.getKey());
                entry.setSize(file.getValue().length);
                entry.setMode(0644);
                tar.putArchiveEntry(entry);
                tar.write(file.getValue());
                tar.closeArchiveEntry();
            }
            tar.finish();
        } catch (IOException e) {
            throw new UncheckedIOException("打包技能资源失败", e);
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }
}
