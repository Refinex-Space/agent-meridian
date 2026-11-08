package cn.refinex.meridian.engine.agentscope.tools;

import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import java.util.List;
import java.util.Map;

/**
 * {@code edit_file}：在工作区的文本文件中做精确的字符串替换；规则内容为路径 glob，副作用等级 {@code idempotent}。
 *
 * <p>要求本线程读过文件的当前版本（"先读后改"，见 {@link FileToolSupport}）。替换在 {@code RemoteFilesystem.edit} 中以版本号
 * 做乐观并发（{@code putIfVersion}），并发写入时重读重试，不会丢失别人的修改。
 */
public final class EditFileTool extends PlatformTool {

    static final String DESCRIPTION = """
            Replace exact text in an existing text file in the session workspace.

            When to use: to change part of a file (fix a sentence, update a value, rename something) without \
            rewriting the whole file.

            Parameters:
            - path: file path relative to the workspace root.
            - old_string: the exact text to replace, copied from read_file output WITHOUT the line-number prefix, \
            with identical whitespace and indentation.
            - new_string: the replacement text; must differ from old_string.
            - replace_all: optional, true to replace every occurrence (default false).

            Rules:
            - You must read the file with read_file before editing it, and the file must not have changed since; \
            otherwise the call fails and you must read it again.
            - Without replace_all, old_string must occur exactly once; include more surrounding text to make it unique.
            - AGENTS.md, MEMORY.md, tools.json, skills/ and subagents/ are platform-managed and cannot be edited.

            Output: a confirmation with the number of replacements made.""";

    /**
     * 创建工具。
     *
     * @param services 平台服务
     */
    public EditFileTool(ToolServices services) {
        super(
                new Spec(
                        PlatformToolNames.EDIT_FILE,
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
                                                "old_string",
                                                        Map.of(
                                                                "type", "string",
                                                                "description", "Exact text to replace."),
                                                "new_string",
                                                        Map.of(
                                                                "type", "string",
                                                                "description", "Replacement text."),
                                                "replace_all",
                                                        Map.of(
                                                                "type", "boolean",
                                                                "description",
                                                                        "Replace every occurrence (default false).")),
                                "required", List.of("path", "old_string", "new_string")),
                        SideEffect.IDEMPOTENT,
                        RuleSyntax.pathGlob("path"),
                        false),
                services);
    }

    @Override
    protected ToolOutput execute(ToolCall call) {
        String path = FileToolSupport.path(call, "path", true);
        String oldString = call.string("old_string");
        String newString = call.string("new_string");
        if (oldString == null || oldString.isEmpty()) {
            throw new ToolInputException("Parameter 'old_string' must not be empty; use write_file to create a file.");
        }
        if (newString == null) {
            throw new ToolInputException("Missing required parameter 'new_string'.");
        }
        if (oldString.equals(newString)) {
            throw new ToolInputException("'old_string' and 'new_string' are identical; nothing to change.");
        }
        String current = FileToolSupport.readWhole(call, path)
                .orElseThrow(
                        () -> new ToolInputException("File '" + path + "' not found. Use write_file to create it."));
        FileToolSupport.requireReadUnchanged(call, path, current);
        EditResult result = call.filesystem()
                .edit(
                        call.runtimeContext(),
                        WorkspacePaths.toFilesystem(path),
                        oldString,
                        newString,
                        call.bool("replace_all", false));
        if (!result.isSuccess()) {
            throw new ToolInputException("Cannot edit '" + path + "': " + result.error());
        }
        String updated = FileToolSupport.readWhole(call, path).orElse("");
        int occurrences = result.occurrences() == null ? 1 : result.occurrences();
        return FileToolSupport.withFileState(
                ToolOutput.ok("Edited '" + path + "': " + occurrences + " replacement(s)."), path, updated);
    }
}
