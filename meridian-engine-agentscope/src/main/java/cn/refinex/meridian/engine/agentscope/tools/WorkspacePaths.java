package cn.refinex.meridian.engine.agentscope.tools;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 工作区路径的规范化与路径 glob 匹配，文件类工具与它们的权限规则共用（架构设计「平台工具」「权限判定」）。
 *
 * <p>平台的路径一律是相对工作区根的路径：不带开头的 {@code /} 与 {@code ./}，分隔符为 {@code /}，不含 {@code .} 与
 * {@code ..} 段，空串表示工作区根。交给文件系统时在前面加 {@code /}（{@link #toFilesystem(String)}）。规则内容中的路径 glob
 * 同样相对工作区根，{@code **} 匹配零或多级目录：{@code **}{@code /x} 也匹配根下的 {@code x}，{@code dir/**} 也匹配
 * {@code dir} 本身（ADR-0031 的平台安全规则依赖这一约定）。
 *
 * <p>纯函数，线程安全。
 */
public final class WorkspacePaths {

    private WorkspacePaths() {}

    /**
     * 把模型给出的路径规范化为相对工作区根的路径。
     *
     * @param raw 原始路径，可以带开头的 {@code /} 或 {@code ./}；为空表示工作区根
     * @return 规范化的路径；路径越出工作区（含 {@code ..} 段）时为空
     */
    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.of("");
        }
        String path = raw.strip().replace('\\', '/');
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                return Optional.empty();
            }
            segments.add(segment);
        }
        return Optional.of(String.join("/", segments));
    }

    /**
     * 相对路径对应的文件系统路径（以 {@code /} 开头；工作区根为 {@code /}）。
     *
     * @param relative 规范化的相对路径
     * @return 文件系统路径
     */
    public static String toFilesystem(String relative) {
        return "/" + relative;
    }

    /**
     * 文件系统返回的路径转回相对路径。
     *
     * @param filesystemPath 文件系统路径
     * @return 相对路径；目录保留结尾的 {@code /}
     */
    public static String fromFilesystem(String filesystemPath) {
        String path = filesystemPath;
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }

    /**
     * 判断路径是否匹配一条规则内容中的路径 glob。
     *
     * @param glob 路径 glob，相对工作区根（开头的 {@code /}、{@code ./} 会被忽略）
     * @param relative 规范化的相对路径
     * @return 匹配时为 true
     */
    public static boolean matches(String glob, String relative) {
        String pattern = normalizeGlob(glob);
        if (pattern.isEmpty()) {
            return relative.isEmpty();
        }
        if (relative.isEmpty()) {
            return pattern.equals("**");
        }
        if (matcher(pattern).matches(Path.of(relative))) {
            return true;
        }
        if (pattern.startsWith("**/") && matches(pattern.substring(3), relative)) {
            return true;
        }
        return pattern.endsWith("/**") && relative.equals(pattern.substring(0, pattern.length() - 3));
    }

    private static String normalizeGlob(String glob) {
        String pattern = glob.strip().replace('\\', '/');
        while (pattern.startsWith("./") || pattern.startsWith("/")) {
            pattern = pattern.startsWith("./") ? pattern.substring(2) : pattern.substring(1);
        }
        return pattern;
    }

    private static PathMatcher matcher(String pattern) {
        return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    }
}
