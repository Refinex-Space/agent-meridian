package cn.refinex.meridian.runtime.workspace.domain;

import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.engine.spi.driver.SandboxDriver;
import java.util.List;

/**
 * 工作区 API 的路径规则（ADR-0050）。路径是相对工作区根的路径，分隔符 {@code /}；与文件工具不同，这里不做规范化，不规整的写法一律拒绝，
 * 调用方看到的路径与存储中的完全相同。
 *
 * <p>纯函数，线程安全。
 */
public final class WorkspaceApiPaths {

    /** 路径的字符上限，与规范中的 {@code maxLength} 一致。 */
    public static final int MAX_LENGTH = 1024;

    /** 受保护路径，与平台安全规则的 {@code PROTECTED_WRITE_PATHS} 一致：单文件，或以 {@code /} 结尾的目录。 */
    static final List<String> PROTECTED = List.of("AGENTS.md", "skills/", "subagents/", "tools.json", "MEMORY.md");

    /** {@code sandbox} 档经 API 可读的目录前缀。 */
    public static final String OUTPUTS_PREFIX = SandboxDriver.OUTPUTS_DIR + "/";

    private WorkspaceApiPaths() {}

    /**
     * 校验文件路径。
     *
     * @param path 路径
     * @return 原样的路径
     * @throws MeridianException 不合法时（{@code workspace.invalid_path}）
     */
    public static String requireValid(String path) {
        if (path == null
                || path.isEmpty()
                || path.length() > MAX_LENGTH
                || path.startsWith("/")
                || path.endsWith("/")) {
            throw invalid(path);
        }
        if (path.indexOf('\\') >= 0 || path.chars().anyMatch(Character::isISOControl)) {
            throw invalid(path);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw invalid(path);
            }
        }
        return path;
    }

    /**
     * 校验列表的路径前缀：可以以 {@code /} 结尾（目录），其余规则同文件路径。
     *
     * @param prefix 前缀；可为空
     * @return 前缀；为空时为空串
     * @throws MeridianException 不合法时
     */
    public static String requireValidPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "";
        }
        requireValid(prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix);
        return prefix;
    }

    /**
     * 是否为受保护路径（对智能体与调用方都只读）。
     *
     * @param path 合法的路径
     * @return 是受保护路径时为 true
     */
    public static boolean isProtected(String path) {
        return PROTECTED.stream()
                .anyMatch(entry -> entry.endsWith("/")
                        ? path.startsWith(entry) || path.equals(entry.substring(0, entry.length() - 1))
                        : path.equals(entry));
    }

    private static MeridianException invalid(String path) {
        return new MeridianException(WorkspaceErrorCode.INVALID_PATH, "工作区路径不合法：" + path);
    }
}
