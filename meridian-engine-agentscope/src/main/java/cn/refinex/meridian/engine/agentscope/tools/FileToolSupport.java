package cn.refinex.meridian.engine.agentscope.tools;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 文件类工具共用的逻辑：路径参数、整文件读取、"先读后改"的读取状态、按拒绝规则过滤搜索结果。
 *
 * <p>"先读后改"参考 Claude Code 的读取状态机制（只学机制，未复制代码）：修改已有文件之前，本线程必须读过它，且读取之后文件没有
 * 变化。Claude Code 把读取状态放在进程内存，平台的一轮可能在任意节点执行，所以读取状态随工具结果保存：{@code read_file}、
 * {@code write_file}、{@code edit_file} 的结果元数据带 {@value #FILE_STATE}（路径与整文件内容的 SHA-256），它随
 * {@code AgentState} 受租约约束地持久化；修改前在本线程的上下文中从新到旧找这条记录，与文件当前内容的哈希比较。上下文被压缩
 * 之后记录随之消失，模型需要重新读取——这时它确实已经看不到文件内容了。
 */
final class FileToolSupport {

    /** 读取状态的元数据键：值为 {@code {path, sha256}}。元数据不发给模型。 */
    static final String FILE_STATE = "meridian_file";

    /** 整文件读取的大小上限，单位字符：更大的文件分段读取也要先整体载入，拒绝它以免占满内存。 */
    static final int MAX_FILE_CHARS = 10 * 1024 * 1024;

    private FileToolSupport() {}

    /**
     * 解析路径参数。
     *
     * @param call 调用
     * @param parameter 参数名
     * @param required 是否必填；不必填时缺省为工作区根
     * @return 规范化的相对路径
     * @throws ToolInputException 缺少必填路径或路径越出工作区时
     */
    static String path(ToolCall call, String parameter, boolean required) {
        String raw = call.string(parameter);
        if (required && (raw == null || raw.isBlank())) {
            throw new ToolInputException("Missing required parameter '" + parameter + "'.");
        }
        Optional<String> normalized = WorkspacePaths.normalize(raw);
        if (normalized.isEmpty()) {
            throw new ToolInputException(
                    "Path '" + raw + "' escapes the workspace; use a path relative to the workspace root.");
        }
        if (required && normalized.get().isEmpty()) {
            throw new ToolInputException("Parameter '" + parameter + "' must name a file, not the workspace root.");
        }
        return normalized.get();
    }

    /**
     * 读取整个文件。
     *
     * @param call 调用
     * @param relative 相对路径
     * @return 文件内容；文件不存在时为空
     * @throws ToolInputException 文件是二进制或超过大小上限时
     */
    static Optional<String> readWhole(ToolCall call, String relative) {
        ReadResult result = call.filesystem().read(call.runtimeContext(), WorkspacePaths.toFilesystem(relative), 0, 0);
        if (!result.isSuccess() || result.fileData() == null) {
            return Optional.empty();
        }
        FileData data = result.fileData();
        if ("base64".equalsIgnoreCase(data.encoding())) {
            throw new ToolInputException("'" + relative + "' is a binary file and cannot be read as text.");
        }
        String content = data.content() == null ? "" : data.content();
        if (content.length() > MAX_FILE_CHARS) {
            throw new ToolInputException(
                    "'" + relative + "' is larger than 10 MB and cannot be read or edited with this tool.");
        }
        return Optional.of(content);
    }

    /**
     * 文件内容的 SHA-256（十六进制）。
     *
     * @param content 内容
     * @return 哈希
     */
    static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    /**
     * 给结果附上读取状态。
     *
     * @param output 结果
     * @param relative 相对路径
     * @param content 模型此刻已知的整文件内容
     * @return 带读取状态的结果
     */
    static ToolOutput withFileState(ToolOutput output, String relative, String content) {
        return output.with(FILE_STATE, Map.of("path", relative, "sha256", sha256(content)));
    }

    /**
     * 检查"先读后改"：本线程读过该文件，且读取之后文件没有变化。
     *
     * @param call 调用
     * @param relative 相对路径
     * @param current 文件当前的整体内容
     * @throws ToolInputException 没有读过或读过之后文件已变化时
     */
    static void requireReadUnchanged(ToolCall call, String relative, String current) {
        Optional<String> known = knownHash(call.runtimeContext(), relative);
        if (known.isEmpty()) {
            throw new ToolInputException("You must read '" + relative + "' with read_file before modifying it.");
        }
        if (!known.get().equals(sha256(current))) {
            throw new ToolInputException(
                    "'" + relative + "' has changed since you last read it. Read it again with read_file, then retry.");
        }
    }

    /** 在本线程的上下文中从新到旧找该路径最近一次的读取状态。 */
    private static Optional<String> knownHash(RuntimeContext context, String relative) {
        AgentState state = context.getAgentState();
        if (state == null) {
            return Optional.empty();
        }
        List<Msg> messages = state.getContext();
        for (int i = messages.size() - 1; i >= 0; i--) {
            List<ToolResultBlock> results = messages.get(i).getContentBlocks(ToolResultBlock.class);
            for (int j = results.size() - 1; j >= 0; j--) {
                Object recorded = results.get(j).getMetadata().get(FILE_STATE);
                if (recorded instanceof Map<?, ?> fileState
                        && relative.equals(fileState.get("path"))
                        && fileState.get("sha256") instanceof String hash) {
                    return Optional.of(hash);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 判断一个文件是否被本工具的拒绝规则覆盖。目录级的工具（{@code grep_files}）按搜索目录做权限判定，命中的文件再按这里过滤，
     * 例如平台安全规则 {@code grep_files(**}{@code /.env)} 让 {@code .env} 不出现在搜索结果中（ADR-0031）。
     *
     * @param call 调用
     * @param tool 工具
     * @param relative 文件的相对路径
     * @param parameter 工具的路径参数名
     * @return 被拒绝规则覆盖时为 true
     */
    static boolean denied(ToolCall call, PlatformTool tool, String relative, String parameter) {
        AgentState state = call.runtimeContext().getAgentState();
        PermissionContextState permissions = state == null ? null : state.getPermissionContext();
        if (permissions == null) {
            return false;
        }
        List<PermissionRule> rules = permissions.getDenyRules().getOrDefault(tool.getName(), List.of());
        Map<String, Object> probe = Map.of(parameter, relative);
        return rules.stream().anyMatch(rule -> tool.matchRule(rule.ruleContent(), probe));
    }
}
