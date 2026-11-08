package cn.refinex.meridian.engine.agentscope.tools;

import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * {@code grep_files}：用正则表达式搜索工作区文件的内容；规则内容为路径 glob，副作用等级 {@code none}。
 *
 * <p>权限按搜索目录判定；命中的文件再按本工具的拒绝规则逐个过滤（{@link FileToolSupport#denied}），平台安全规则因此对目录级
 * 搜索同样有效，例如 {@code .env} 的内容不会出现在结果中。最多扫描 {@value #MAX_FILES} 个文件、跳过超过
 * {@value #MAX_FILE_CHARS} 字符的文件，结果最多 {@value #MAX_RESULTS_LIMIT} 行、每行最多 {@value #MAX_LINE_CHARS} 字符。
 */
public final class GrepFilesTool extends PlatformTool {

    /** 默认返回的匹配行数。 */
    static final int DEFAULT_RESULTS = 100;

    /** 返回的匹配行数上限。 */
    static final int MAX_RESULTS_LIMIT = 500;

    /** 一次最多扫描的文件数。 */
    static final int MAX_FILES = 1000;

    /** 单个文件的字符上限，更大的文件跳过。 */
    static final int MAX_FILE_CHARS = 1024 * 1024;

    /** 匹配行的字符上限。 */
    static final int MAX_LINE_CHARS = 500;

    static final String DESCRIPTION = """
            Search the contents of files in the session workspace with a regular expression.

            When to use: to find where a word, name or phrase appears across files. To find files by name, use \
            glob_files; to read a whole file, use read_file.

            Parameters:
            - pattern: Java regular expression, e.g. "revenue\\s+2024" or "TODO|FIXME". Escape regex special \
            characters to search for them literally.
            - path: optional directory (or single file) to search, relative to the workspace root; omit for the whole workspace.
            - glob: optional file-name filter, e.g. "*.md" or "*.{csv,txt}".
            - ignore_case: optional, true for case-insensitive matching (default false).
            - max_results: optional maximum number of matching lines (default 100, at most 500).

            Output: one match per line as "<path>:<line number>: <line text>", grouped by file in path order. Long \
            lines are truncated to 500 characters. Binary files and files larger than 1 MB are skipped.""";

    /**
     * 创建工具。
     *
     * @param services 平台服务
     */
    public GrepFilesTool(ToolServices services) {
        super(
                new Spec(
                        PlatformToolNames.GREP_FILES,
                        DESCRIPTION,
                        Map.of(
                                "type", "object",
                                "properties",
                                        Map.of(
                                                "pattern",
                                                        Map.of(
                                                                "type",
                                                                "string",
                                                                "description",
                                                                "Java regular expression."),
                                                "path",
                                                        Map.of(
                                                                "type", "string",
                                                                "description",
                                                                        "Directory or file to search, relative to the workspace root."),
                                                "glob",
                                                        Map.of(
                                                                "type", "string",
                                                                "description", "File-name filter, e.g. \"*.md\"."),
                                                "ignore_case",
                                                        Map.of(
                                                                "type", "boolean",
                                                                "description",
                                                                        "Case-insensitive matching (default false)."),
                                                "max_results",
                                                        Map.of(
                                                                "type",
                                                                "integer",
                                                                "minimum",
                                                                1,
                                                                "maximum",
                                                                MAX_RESULTS_LIMIT,
                                                                "description",
                                                                "Maximum matching lines (default 100).")),
                                "required", List.of("pattern")),
                        SideEffect.NONE,
                        RuleSyntax.pathGlob("path"),
                        true),
                services);
    }

    @Override
    protected ToolOutput execute(ToolCall call) {
        String raw = call.string("pattern");
        if (raw == null || raw.isEmpty()) {
            throw new ToolInputException("Missing required parameter 'pattern'.");
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(raw, call.bool("ignore_case", false) ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            throw new ToolInputException("Invalid regular expression '" + raw + "': " + e.getDescription());
        }
        int maxResults = Math.clamp(call.integer("max_results", DEFAULT_RESULTS), 1, MAX_RESULTS_LIMIT);
        String path = FileToolSupport.path(call, "path", false);
        List<String> candidates = candidates(call, path, call.string("glob"));

        StringBuilder out = new StringBuilder();
        int matches = 0;
        int scanned = 0;
        boolean truncated = false;
        for (String file : candidates) {
            if (matches >= maxResults) {
                truncated = true;
                break;
            }
            if (scanned == MAX_FILES) {
                truncated = true;
                break;
            }
            if (FileToolSupport.denied(call, this, file, "path")) {
                continue;
            }
            scanned++;
            Optional<String> content = readText(call, file);
            if (content.isEmpty()) {
                continue;
            }
            String[] lines = content.get().split("\n", -1);
            for (int i = 0; i < lines.length && matches < maxResults; i++) {
                Matcher matcher = pattern.matcher(lines[i]);
                if (matcher.find()) {
                    String line = lines[i].length() > MAX_LINE_CHARS
                            ? lines[i].substring(0, MAX_LINE_CHARS) + " …"
                            : lines[i];
                    out.append(file)
                            .append(':')
                            .append(i + 1)
                            .append(": ")
                            .append(line)
                            .append('\n');
                    matches++;
                }
            }
        }
        if (matches == 0) {
            return ToolOutput.ok("(no matches for '" + raw + "' in " + scanned + " files)");
        }
        if (truncated || matches >= maxResults) {
            out.append("[Stopped after ")
                    .append(matches)
                    .append(" matches in ")
                    .append(scanned)
                    .append(" files. Narrow the search with path or glob to see more.]");
        }
        return ToolOutput.ok(out.toString().stripTrailing());
    }

    /** 候选文件：路径是文件时只有它自己，否则按文件名过滤列出目录下的全部文件。 */
    private static List<String> candidates(ToolCall call, String path, String glob) {
        String fsPath = WorkspacePaths.toFilesystem(path);
        if (!path.isEmpty()
                && call.filesystem().read(call.runtimeContext(), fsPath, 0, 1).isSuccess()) {
            return List.of(path);
        }
        String filter = glob == null || glob.isBlank() ? "**/*" : glob.strip();
        GlobResult result = call.filesystem().glob(call.runtimeContext(), filter, fsPath);
        if (!result.isSuccess() || result.matches() == null) {
            return List.of();
        }
        return result.matches().stream()
                .filter(f -> !f.isDirectory())
                .map(FileInfo::path)
                .map(WorkspacePaths::fromFilesystem)
                .distinct()
                .sorted()
                .toList();
    }

    private static Optional<String> readText(ToolCall call, String file) {
        try {
            return FileToolSupport.readWhole(call, file).filter(c -> c.length() <= MAX_FILE_CHARS);
        } catch (ToolInputException e) {
            // 二进制或过大的文件不搜索
            return Optional.empty();
        }
    }
}
