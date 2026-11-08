package cn.refinex.meridian.engine.agentscope.fs;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 只读的文件系统：受保护路径（{@code AGENTS.md}、{@code skills/}、{@code subagents/}、{@code tools.json}、{@code MEMORY.md}）
 * 在 {@code CompositeFilesystem} 中路由到它（架构设计「文件系统装配」）。读取委托给内容来源，所有写操作一律返回失败结果，
 * 智能体因此不能通过写文件改写自己的提示词、技能或子智能体。
 *
 * <p>路径是后端视角：目录路由（如 {@code /skills/}）被剥离前缀后为 {@code /<name>/SKILL.md}，单文件路由（如
 * {@code /AGENTS.md}）保持原样。内容是 UTF-8 文本。
 *
 * <p>线程安全：不可变；内容来源必须线程安全。
 */
public final class ReadOnlyFilesystem implements AbstractFilesystem {

    /** 写操作失败时给出的说明。 */
    static final String READ_ONLY = "is a protected platform path and is read-only";

    private final ContentSource source;

    /**
     * 创建只读文件系统。
     *
     * @param source 内容来源
     */
    public ReadOnlyFilesystem(ContentSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * 内容固定的只读文件系统。
     *
     * @param files 文件：后端路径（以 {@code /} 开头）→ 文本内容
     * @return 文件系统
     */
    public static ReadOnlyFilesystem of(Map<String, String> files) {
        Map<String, String> copy = Map.copyOf(files);
        return new ReadOnlyFilesystem(ctx -> copy);
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        String dir = directory(path);
        List<FileInfo> entries = new ArrayList<>();
        Set<String> subdirs = new LinkedHashSet<>();
        for (Map.Entry<String, String> file : files(runtimeContext).entrySet()) {
            if (!file.getKey().startsWith(dir)) {
                continue;
            }
            String relative = file.getKey().substring(dir.length());
            int slash = relative.indexOf('/');
            if (slash >= 0) {
                subdirs.add(dir + relative.substring(0, slash) + "/");
            } else {
                entries.add(FileInfo.ofFile(file.getKey(), size(file.getValue()), ""));
            }
        }
        subdirs.forEach(d -> entries.add(FileInfo.ofDir(d, "")));
        entries.sort(Comparator.comparing(FileInfo::path));
        return LsResult.success(entries);
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        String content = files(runtimeContext).get(file(filePath));
        if (content == null) {
            return ReadResult.fail("File '" + filePath + "' not found");
        }
        if (offset <= 0 && limit <= 0) {
            return ReadResult.success(FileData.create(content));
        }
        String[] lines = content.split("\n", -1);
        int start = Math.max(0, offset);
        if (start >= lines.length) {
            return ReadResult.fail("Line offset " + offset + " exceeds file length (" + lines.length + " lines)");
        }
        int end = limit > 0 ? Math.min(start + limit, lines.length) : lines.length;
        return ReadResult.success(
                FileData.create(String.join("\n", List.of(lines).subList(start, end))));
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        return WriteResult.fail(filePath + " " + READ_ONLY);
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext, String filePath, String oldString, String newString, boolean replaceAll) {
        return EditResult.fail(filePath + " " + READ_ONLY);
    }

    @Override
    public GrepResult grep(RuntimeContext runtimeContext, String pattern, String path, String glob) {
        String prefix = path == null ? "/" : directory(path);
        String exact = path == null ? null : file(path);
        PathMatcher nameMatcher =
                glob == null || glob.isBlank() ? null : FileSystems.getDefault().getPathMatcher("glob:" + glob);
        List<GrepMatch> matches = new ArrayList<>();
        files(runtimeContext).entrySet().stream()
                .filter(f -> f.getKey().equals(exact) || f.getKey().startsWith(prefix))
                .filter(f -> nameMatcher == null || nameMatcher.matches(Path.of(fileName(f.getKey()))))
                .sorted(Map.Entry.comparingByKey())
                .forEach(f -> {
                    String[] lines = f.getValue().split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        if (lines[i].contains(pattern)) {
                            matches.add(new GrepMatch(f.getKey(), i + 1, lines[i]));
                        }
                    }
                });
        return GrepResult.success(matches);
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        String dir = directory(path);
        String effective = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        PathMatcher recursive = FileSystems.getDefault()
                .getPathMatcher("glob:" + (effective.startsWith("**") ? effective : "**/" + effective));
        String direct = effective.startsWith("**/") ? effective.substring(3) : effective.equals("**") ? "*" : effective;
        PathMatcher directMatcher = FileSystems.getDefault().getPathMatcher("glob:" + direct);
        List<FileInfo> matches = new ArrayList<>();
        for (Map.Entry<String, String> file : files(runtimeContext).entrySet()) {
            if (!file.getKey().startsWith(dir)) {
                continue;
            }
            Path relative = Path.of(file.getKey().substring(dir.length()));
            if (recursive.matches(relative) || directMatcher.matches(relative)) {
                matches.add(FileInfo.ofFile(file.getKey(), size(file.getValue()), ""));
            }
        }
        matches.sort(Comparator.comparing(FileInfo::path));
        return GlobResult.success(matches);
    }

    @Override
    public List<FileUploadResponse> uploadFiles(RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        return files.stream()
                .map(f -> FileUploadResponse.fail(f.getKey(), f.getKey() + " " + READ_ONLY))
                .toList();
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(RuntimeContext runtimeContext, List<String> paths) {
        Map<String, String> files = files(runtimeContext);
        return paths.stream()
                .map(p -> {
                    String content = files.get(file(p));
                    return content == null
                            ? FileDownloadResponse.fail(p, "file_not_found")
                            : FileDownloadResponse.success(p, content.getBytes(StandardCharsets.UTF_8));
                })
                .toList();
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        return WriteResult.fail(path + " " + READ_ONLY);
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        return WriteResult.fail(fromPath + " " + READ_ONLY);
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String exact = file(path);
        String dir = directory(path);
        return files(runtimeContext).keySet().stream().anyMatch(k -> k.equals(exact) || k.startsWith(dir));
    }

    private Map<String, String> files(RuntimeContext runtimeContext) {
        Map<String, String> files = source.files(runtimeContext);
        return files == null ? Map.of() : files;
    }

    /** 后端路径：恰好一个开头的 {@code /}（{@code CompositeFilesystem} 对单文件路由会传 {@code //AGENTS.md}），不带结尾的 {@code /}。 */
    private static String file(String path) {
        String normalized = "/" + path.replaceFirst("^/+", "");
        return normalized.length() > 1 && normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    private static String directory(String path) {
        if (path == null || path.isBlank() || path.equals(".") || path.equals("/")) {
            return "/";
        }
        String normalized = file(path);
        return normalized + "/";
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static long size(String content) {
        return content.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 只读文件的内容来源；内容可以按调用的上下文变化（例如二期按终端用户生成 {@code MEMORY.md}）。 */
    @FunctionalInterface
    public interface ContentSource {

        /**
         * 返回本次调用可见的文件。
         *
         * @param runtimeContext 调用的上下文
         * @return 后端路径 → 文本内容
         */
        Map<String, String> files(RuntimeContext runtimeContext);
    }
}
