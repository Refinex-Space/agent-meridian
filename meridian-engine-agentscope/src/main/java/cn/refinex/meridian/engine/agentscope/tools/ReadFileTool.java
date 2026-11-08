package cn.refinex.meridian.engine.agentscope.tools;

import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import java.util.List;
import java.util.Map;

/**
 * {@code read_file}：读取工作区中的文本文件，按行号输出；规则内容为路径 glob，副作用等级 {@code none}（架构设计「平台工具」）。
 *
 * <p>输出有上限：默认最多 {@value #DEFAULT_LIMIT} 行，每行最多 {@value #MAX_LINE_CHARS} 字符，总长最多 {@value #MAX_OUTPUT_CHARS}
 * 字符（低于工具结果卸载的 80000 字符阈值，结果不会被卸载到 {@code large_tool_results/}）。截断时在末尾说明如何用
 * {@code offset} 继续读取。结果带读取状态，供 {@code edit_file}、{@code write_file} 检查"先读后改"。
 */
public final class ReadFileTool extends PlatformTool {

    /** 默认读取的行数。 */
    static final int DEFAULT_LIMIT = 2000;

    /** 单行的字符上限，超出部分截断。 */
    static final int MAX_LINE_CHARS = 2000;

    /** 一次输出的字符上限。 */
    static final int MAX_OUTPUT_CHARS = 50_000;

    static final String DESCRIPTION = """
            Read a text file from the session workspace and return its lines with line numbers.

            When to use: to inspect a file's contents before answering, and ALWAYS before modifying an existing file \
            with edit_file or write_file (they fail unless you have read the current version of the file).

            Parameters:
            - path: file path relative to the workspace root, e.g. "reports/q3.md".
            - offset: optional 1-based line number to start from. Omit to start at line 1.
            - limit: optional number of lines to return (default 2000).

            Output: each line as "<line number>\\t<text>" (cat -n style). The line-number prefix is not part of the file; \
            never include it in edit_file's old_string. Lines longer than 2000 characters are truncated. When the output \
            stops before the end of the file, the last line tells you the next offset to continue reading.

            Limits: text files only (binary files are rejected). Use list_files or glob_files to discover paths; \
            directories cannot be read with this tool.""";

    /**
     * 创建工具。
     *
     * @param services 平台服务
     */
    public ReadFileTool(ToolServices services) {
        super(
                new Spec(
                        PlatformToolNames.READ_FILE,
                        DESCRIPTION,
                        Map.of(
                                "type", "object",
                                "properties",
                                        Map.of(
                                                "path",
                                                        Map.of(
                                                                "type", "string",
                                                                "description",
                                                                        "File path relative to the workspace root."),
                                                "offset",
                                                        Map.of(
                                                                "type", "integer",
                                                                "minimum", 1,
                                                                "description",
                                                                        "1-based line number to start reading from."),
                                                "limit",
                                                        Map.of(
                                                                "type", "integer",
                                                                "minimum", 1,
                                                                "description",
                                                                        "Maximum number of lines to return (default 2000).")),
                                "required", List.of("path")),
                        SideEffect.NONE,
                        RuleSyntax.pathGlob("path"),
                        true),
                services);
    }

    @Override
    protected ToolOutput execute(ToolCall call) {
        String path = FileToolSupport.path(call, "path", true);
        int offset = Math.max(1, call.integer("offset", 1));
        int limit = Math.max(1, call.integer("limit", DEFAULT_LIMIT));
        String content = FileToolSupport.readWhole(call, path)
                .orElseThrow(() -> new ToolInputException(
                        "File '" + path + "' not found. Use list_files or glob_files to find the right path."));
        if (content.isEmpty()) {
            return FileToolSupport.withFileState(ToolOutput.ok("(file '" + path + "' is empty)"), path, content);
        }
        String[] lines = content.split("\n", -1);
        int total = content.endsWith("\n") ? lines.length - 1 : lines.length;
        if (offset > total) {
            throw new ToolInputException(
                    "Offset " + offset + " is beyond the end of '" + path + "' (" + total + " lines).");
        }
        StringBuilder out = new StringBuilder();
        int last = offset - 1;
        for (int i = offset - 1; i < total && i < offset - 1 + limit; i++) {
            String line = lines[i];
            if (line.length() > MAX_LINE_CHARS) {
                line = line.substring(0, MAX_LINE_CHARS) + " … [line truncated]";
            }
            String numbered = String.format("%6d\t%s\n", i + 1, line);
            if (out.length() + numbered.length() > MAX_OUTPUT_CHARS) {
                break;
            }
            out.append(numbered);
            last = i + 1;
        }
        if (last < total) {
            out.append("[Showing lines ")
                    .append(offset)
                    .append('-')
                    .append(last)
                    .append(" of ")
                    .append(total)
                    .append(". Call read_file with offset=")
                    .append(last + 1)
                    .append(" to continue.]");
        }
        return FileToolSupport.withFileState(ToolOutput.ok(out.toString().stripTrailing()), path, content);
    }
}
