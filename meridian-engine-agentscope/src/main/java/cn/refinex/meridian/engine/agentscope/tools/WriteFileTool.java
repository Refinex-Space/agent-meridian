package cn.refinex.meridian.engine.agentscope.tools;

import cn.refinex.meridian.engine.spi.spec.PlatformToolNames;
import cn.refinex.meridian.engine.spi.spec.SideEffect;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code write_file}：创建或整体覆盖工作区中的文本文件；规则内容为路径 glob，副作用等级 {@code idempotent}（同样的内容写两次结果
 * 相同）。覆盖已有文件前要求本线程读过它的当前版本（"先读后改"）；受保护路径由平台安全规则拒绝，即使规则被绕过，只读文件系统
 * 也会拒绝写入。
 */
public final class WriteFileTool extends PlatformTool {

    static final String DESCRIPTION = """
            Create a new text file in the session workspace, or replace the entire contents of an existing one.

            When to use: to create a new file, or to rewrite a file completely. For changing part of an existing \
            file, prefer edit_file, which is safer and cheaper.

            Parameters:
            - path: file path relative to the workspace root; parent directories are created implicitly.
            - content: the complete new contents of the file (UTF-8 text).

            Rules:
            - Overwriting an existing file requires that you read its current version with read_file first; \
            otherwise the call fails and you must read the file and retry.
            - AGENTS.md, MEMORY.md, tools.json, skills/ and subagents/ are platform-managed and cannot be written.
            - Content larger than 10 MB is rejected.

            Output: a confirmation with the number of bytes written.""";

    /**
     * 创建工具。
     *
     * @param services 平台服务
     */
    public WriteFileTool(ToolServices services) {
        super(
                new Spec(
                        PlatformToolNames.WRITE_FILE,
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
                                                "content",
                                                        Map.of(
                                                                "type", "string",
                                                                "description", "Complete new contents of the file.")),
                                "required", List.of("path", "content")),
                        SideEffect.IDEMPOTENT,
                        RuleSyntax.pathGlob("path"),
                        false),
                services);
    }

    @Override
    protected ToolOutput execute(ToolCall call) {
        String path = FileToolSupport.path(call, "path", true);
        String content = call.string("content");
        if (content == null) {
            throw new ToolInputException("Missing required parameter 'content'.");
        }
        if (content.length() > FileToolSupport.MAX_FILE_CHARS) {
            throw new ToolInputException("Content is larger than 10 MB; split it into several files.");
        }
        String fsPath = WorkspacePaths.toFilesystem(path);
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        Optional<String> existing = FileToolSupport.readWhole(call, path);
        if (existing.isEmpty()) {
            WriteResult created = call.filesystem().write(call.runtimeContext(), fsPath, content);
            if (!created.isSuccess()) {
                throw new ToolInputException("Cannot write '" + path + "': " + created.error());
            }
            return FileToolSupport.withFileState(
                    ToolOutput.ok("Created '" + path + "' (" + bytes + " bytes)."), path, content);
        }
        FileToolSupport.requireReadUnchanged(call, path, existing.get());
        List<FileUploadResponse> responses = call.filesystem()
                .uploadFiles(
                        call.runtimeContext(), List.of(Map.entry(fsPath, content.getBytes(StandardCharsets.UTF_8))));
        FileUploadResponse response = responses.isEmpty() ? null : responses.getFirst();
        if (response == null || !response.isSuccess()) {
            throw new ToolInputException(
                    "Cannot write '" + path + "': " + (response == null ? "no response" : response.error()));
        }
        return FileToolSupport.withFileState(
                ToolOutput.ok("Overwrote '" + path + "' (" + bytes + " bytes)."), path, content);
    }
}
