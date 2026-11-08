package cn.refinex.meridian.engine.agentscope.tools;

import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import java.util.List;
import java.util.Map;

/**
 * {@code glob_files}：按文件名模式在工作区中查找文件；规则内容为路径 glob（与搜索目录比较），副作用等级 {@code none}。
 * 最多返回 {@value #MAX_RESULTS} 个路径。
 */
public final class GlobFilesTool extends PlatformTool {

    /** 一次最多返回的路径数。 */
    static final int MAX_RESULTS = 500;

    static final String DESCRIPTION = """
            Find files in the session workspace whose paths match a glob pattern.

            When to use: to locate files by name or extension, e.g. all Markdown reports. To search inside files, \
            use grep_files; to list a single directory, use list_files.

            Parameters:
            - pattern: glob relative to the search directory. "*" matches within one path segment, "**" matches \
            any number of directories. Examples: "**/*.md", "reports/*.csv", "*.json".
            - path: optional directory to search in, relative to the workspace root; omit to search the whole workspace.

            Output: matching file paths relative to the workspace root, one per line, sorted. At most 500 paths.""";

    /**
     * 创建工具。
     *
     * @param services 平台服务
     */
    public GlobFilesTool(ToolServices services) {
        super(
                new Spec(
                        PlatformToolNames.GLOB_FILES,
                        DESCRIPTION,
                        Map.of(
                                "type", "object",
                                "properties",
                                        Map.of(
                                                "pattern",
                                                        Map.of(
                                                                "type", "string",
                                                                "description", "Glob pattern, e.g. \"**/*.md\"."),
                                                "path",
                                                        Map.of(
                                                                "type",
                                                                "string",
                                                                "description",
                                                                "Directory to search in, relative to the workspace root; "
                                                                        + "omit for the whole workspace.")),
                                "required", List.of("pattern")),
                        SideEffect.NONE,
                        RuleSyntax.pathGlob("path"),
                        true),
                services);
    }

    @Override
    protected ToolOutput execute(ToolCall call) {
        String pattern = call.string("pattern");
        if (pattern == null || pattern.isBlank()) {
            throw new ToolInputException("Missing required parameter 'pattern'.");
        }
        String path = FileToolSupport.path(call, "path", false);
        GlobResult result =
                call.filesystem().glob(call.runtimeContext(), pattern.strip(), WorkspacePaths.toFilesystem(path));
        if (!result.isSuccess()) {
            throw new ToolInputException("Glob '" + pattern + "' failed: " + result.error());
        }
        List<String> matches = (result.matches() == null ? List.<FileInfo>of() : result.matches())
                .stream()
                        .filter(f -> !f.isDirectory())
                        .map(f -> WorkspacePaths.fromFilesystem(f.path()))
                        .distinct()
                        .sorted()
                        .toList();
        if (matches.isEmpty()) {
            return ToolOutput.ok("(no files match '" + pattern + "')");
        }
        StringBuilder out =
                new StringBuilder(String.join("\n", matches.subList(0, Math.min(MAX_RESULTS, matches.size()))));
        if (matches.size() > MAX_RESULTS) {
            out.append("\n[Only the first ")
                    .append(MAX_RESULTS)
                    .append(" of ")
                    .append(matches.size())
                    .append(" matches are shown. Use a more specific pattern or path.]");
        }
        return ToolOutput.ok(out.toString());
    }
}
