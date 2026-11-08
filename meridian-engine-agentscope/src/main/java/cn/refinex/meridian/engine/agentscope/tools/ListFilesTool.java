package cn.refinex.meridian.engine.agentscope.tools;

import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import java.util.List;
import java.util.Map;

/**
 * {@code list_files}：列出工作区中一个目录的直接子项；规则内容为路径 glob（与目录路径比较），副作用等级 {@code none}。
 * 最多列出 {@value #MAX_ENTRIES} 项。
 */
public final class ListFilesTool extends PlatformTool {

    /** 一次最多列出的项数。 */
    static final int MAX_ENTRIES = 1000;

    static final String DESCRIPTION = """
            List the direct children of a directory in the session workspace.

            When to use: to see what files and folders exist at a location. To find files by name pattern across \
            subdirectories, use glob_files; to search file contents, use grep_files.

            Parameters:
            - path: directory relative to the workspace root; omit or use "" for the workspace root.

            Output: one entry per line, directories end with "/", files show their size in bytes when known. \
            At most 1000 entries.

            Notes: AGENTS.md, MEMORY.md, tools.json, skills/ and subagents/ are platform-managed and read-only.""";

    /**
     * 创建工具。
     *
     * @param services 平台服务
     */
    public ListFilesTool(ToolServices services) {
        super(
                new Spec(
                        PlatformToolNames.LIST_FILES,
                        DESCRIPTION,
                        Map.of(
                                "type", "object",
                                "properties",
                                        Map.of(
                                                "path",
                                                Map.of(
                                                        "type", "string",
                                                        "description",
                                                                "Directory relative to the workspace root; omit for the root.")),
                                "required", List.of()),
                        SideEffect.NONE,
                        RuleSyntax.pathGlob("path"),
                        true),
                services);
    }

    @Override
    protected ToolOutput execute(ToolCall call) {
        String path = FileToolSupport.path(call, "path", false);
        LsResult result = call.filesystem().ls(call.runtimeContext(), WorkspacePaths.toFilesystem(path));
        if (!result.isSuccess()) {
            throw new ToolInputException("Cannot list '" + display(path) + "': " + result.error());
        }
        List<FileInfo> entries = result.entries() == null ? List.of() : result.entries();
        if (entries.isEmpty()) {
            return ToolOutput.ok("(directory '" + display(path) + "' is empty or does not exist)");
        }
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (FileInfo entry : entries) {
            if (shown == MAX_ENTRIES) {
                out.append("[Only the first ")
                        .append(MAX_ENTRIES)
                        .append(" of ")
                        .append(entries.size())
                        .append(" entries are shown. List a subdirectory or use glob_files to narrow down.]");
                break;
            }
            String relative = WorkspacePaths.fromFilesystem(entry.path());
            if (entry.isDirectory()) {
                out.append(relative.endsWith("/") ? relative : relative + "/").append('\n');
            } else if (entry.size() > 0) {
                out.append(relative).append("  (").append(entry.size()).append(" bytes)\n");
            } else {
                // 受保护的单文件路由不报告大小（CompositeFilesystem 给出 0），不显示大小以免误以为是空文件
                out.append(relative).append('\n');
            }
            shown++;
        }
        return ToolOutput.ok(out.toString().stripTrailing());
    }

    private static String display(String path) {
        return path.isEmpty() ? "/" : path;
    }
}
