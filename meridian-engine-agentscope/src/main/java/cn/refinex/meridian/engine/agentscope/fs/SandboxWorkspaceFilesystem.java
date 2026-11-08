package cn.refinex.meridian.engine.agentscope.fs;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code sandbox} 档平台工具看到的主文件系统：把平台路径翻译为沙箱中的绝对路径，并要求每次调用都带本次调用绑定的会话沙箱。
 *
 * <p>为什么需要它（AgentScope 2.0.4 已核实）：
 *
 * <ul>
 *   <li>{@code BaseSandboxFilesystem} 把路径原样拼进 shell 命令。平台工具使用以 {@code /} 开头、相对工作区根的路径
 *       （{@code /reports/q3.md}，见 {@code WorkspacePaths}），原样执行会落到容器根目录（根文件系统只读）。本类把它翻译为
 *       {@code /workspace/reports/q3.md}，结果中的路径再翻译回来。
 *   <li>{@code CompositeFilesystem} 列工作区根时向主文件系统传 {@code /}，在沙箱里列出的是容器根目录；经本类翻译后列出的是
 *       {@code /workspace}。
 *   <li>沙箱的 {@code glob} 是 {@code find -name}，只比较文件名；本类按 {@code RemoteFilesystem} 的语义（相对搜索目录的路径
 *       glob，非 {@code **} 开头时也匹配任意深度）在列出的文件上过滤，两个档位的 {@code glob_files} 行为一致。
 *   <li>{@code SandboxBackedFilesystem} 在调用上下文里没有本次调用绑定的沙箱（{@code SandboxAcquireResult}）时，退回到同一
 *       智能体实例上最后绑定的沙箱，而实例被多个会话共享。本类拒绝这种调用，保证一个会话的工具只会操作自己的沙箱。
 * </ul>
 *
 * <p>装配器用它包住 harness 的 {@code SandboxBackedFilesystem}，再以同一组路由组成 {@code RoutedSandboxFilesystem} 交给平台工具；
 * 受保护路径与 {@code large_tool_results/} 由路由处理，不经过本类。线程安全：无状态。
 */
public final class SandboxWorkspaceFilesystem implements AbstractSandboxFilesystem {

    /** 沙箱中的工作区根目录，由沙箱镜像固定（ADR-0036：指向 emptyDir 中工作区目录的符号链接）。 */
    public static final String WORKSPACE_ROOT = "/workspace";

    private final AbstractSandboxFilesystem delegate;

    /**
     * 包装 harness 的沙箱文件系统。
     *
     * @param delegate 沙箱文件系统（{@code RoutedSandboxFilesystem.primary()}）
     */
    public SandboxWorkspaceFilesystem(AbstractSandboxFilesystem delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public ExecuteResponse execute(RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
        return delegate.execute(bound(runtimeContext), command, timeoutSeconds);
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        LsResult result = delegate.ls(bound(runtimeContext), toSandbox(path));
        if (!result.isSuccess() || result.entries() == null) {
            return result;
        }
        return LsResult.success(result.entries().stream()
                .map(SandboxWorkspaceFilesystem::toPlatform)
                .toList());
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        return delegate.read(bound(runtimeContext), toSandbox(filePath), offset, limit);
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        return delegate.write(bound(runtimeContext), toSandbox(filePath), content);
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext, String filePath, String oldString, String newString, boolean replaceAll) {
        return delegate.edit(bound(runtimeContext), toSandbox(filePath), oldString, newString, replaceAll);
    }

    @Override
    public GrepResult grep(RuntimeContext runtimeContext, String pattern, String path, String glob) {
        GrepResult result = delegate.grep(bound(runtimeContext), pattern, toSandbox(path), glob);
        if (!result.isSuccess() || result.matches() == null) {
            return result;
        }
        return GrepResult.success(result.matches().stream()
                .map(m -> new GrepMatch(toPlatform(m.path()), m.line(), m.text()))
                .toList());
    }

    /**
     * 按 {@code RemoteFilesystem} 的语义匹配：模式相对搜索目录；不以 {@code **} 开头的模式也匹配任意深度（{@code *.md} 等价于
     * {@code **}{@code /*.md}）。先列出搜索目录下的全部文件，再在本地过滤。
     */
    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        String base = platformPath(path);
        GlobResult all = delegate.glob(bound(runtimeContext), "*", toSandbox(base));
        if (!all.isSuccess() || all.matches() == null) {
            return all;
        }
        String effective = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        PathMatcher recursive = FileSystems.getDefault()
                .getPathMatcher("glob:" + (effective.startsWith("**") ? effective : "**/" + effective));
        String direct = effective.startsWith("**/") ? effective.substring(3) : effective.equals("**") ? "*" : effective;
        PathMatcher directMatcher = FileSystems.getDefault().getPathMatcher("glob:" + direct);
        String prefix = base.equals("/") ? "/" : base + "/";
        List<FileInfo> matches = new ArrayList<>();
        for (FileInfo file : all.matches()) {
            FileInfo mapped = toPlatform(file);
            if (!mapped.path().startsWith(prefix)) {
                continue;
            }
            Path relative = Path.of(mapped.path().substring(prefix.length()));
            if (recursive.matches(relative) || directMatcher.matches(relative)) {
                matches.add(mapped);
            }
        }
        matches.sort(Comparator.comparing(FileInfo::path));
        return GlobResult.success(matches);
    }

    @Override
    public List<FileUploadResponse> uploadFiles(RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        List<Map.Entry<String, byte[]>> mapped = files.stream()
                .map(f -> Map.entry(toSandbox(f.getKey()), f.getValue()))
                .toList();
        List<FileUploadResponse> responses = delegate.uploadFiles(bound(runtimeContext), mapped);
        List<FileUploadResponse> result = new ArrayList<>(responses.size());
        for (int i = 0; i < responses.size(); i++) {
            FileUploadResponse response = responses.get(i);
            String original = i < files.size() ? files.get(i).getKey() : response.path();
            result.add(
                    response.isSuccess()
                            ? FileUploadResponse.success(original)
                            : FileUploadResponse.fail(original, response.error()));
        }
        return result;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(RuntimeContext runtimeContext, List<String> paths) {
        List<FileDownloadResponse> responses = delegate.downloadFiles(
                bound(runtimeContext),
                paths.stream().map(SandboxWorkspaceFilesystem::toSandbox).toList());
        List<FileDownloadResponse> result = new ArrayList<>(responses.size());
        for (int i = 0; i < responses.size(); i++) {
            FileDownloadResponse response = responses.get(i);
            String original = i < paths.size() ? paths.get(i) : response.path();
            result.add(
                    response.isSuccess()
                            ? FileDownloadResponse.success(original, response.content())
                            : FileDownloadResponse.fail(original, response.error()));
        }
        return result;
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        return delegate.delete(bound(runtimeContext), toSandbox(path));
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        return delegate.move(bound(runtimeContext), toSandbox(fromPath), toSandbox(toPath));
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        return delegate.exists(bound(runtimeContext), toSandbox(path));
    }

    /**
     * 平台路径 → 沙箱中的绝对路径：{@code /reports/q3.md}、{@code reports/q3.md} 都翻译为 {@code /workspace/reports/q3.md}，
     * 工作区根（{@code /}、{@code .}、空）翻译为 {@code /workspace}。
     *
     * @param path 平台路径
     * @return 沙箱路径
     */
    static String toSandbox(String path) {
        String relative = platformPath(path).substring(1);
        return relative.isEmpty() ? WORKSPACE_ROOT : WORKSPACE_ROOT + "/" + relative;
    }

    /**
     * 沙箱路径 → 平台路径：去掉 {@code /workspace} 与开头的 {@code ./}，以 {@code /} 开头。工作区之外的绝对路径原样返回。
     *
     * @param path 沙箱路径
     * @return 平台路径
     */
    static String toPlatform(String path) {
        if (path.equals(WORKSPACE_ROOT)) {
            return "/";
        }
        if (path.startsWith(WORKSPACE_ROOT + "/")) {
            return path.substring(WORKSPACE_ROOT.length());
        }
        if (path.startsWith("/")) {
            return path;
        }
        return platformPath(path);
    }

    private static FileInfo toPlatform(FileInfo file) {
        return new FileInfo(toPlatform(file.path()), file.isDirectory(), file.size(), file.modifiedAt());
    }

    /** 规整为以 {@code /} 开头、不带 {@code ./} 与结尾斜杠的平台路径；工作区根为 {@code /}。 */
    private static String platformPath(String path) {
        String p = path == null ? "" : path.strip();
        while (p.startsWith("./") || p.startsWith("/")) {
            p = p.substring(p.startsWith("./") ? 2 : 1);
        }
        if (p.equals(".")) {
            p = "";
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return "/" + p;
    }

    /** 要求调用上下文带有本次调用绑定的会话沙箱，不允许退回到共享实例上最后绑定的沙箱。 */
    private static RuntimeContext bound(RuntimeContext runtimeContext) {
        if (runtimeContext == null
                || runtimeContext.get(SandboxAcquireResult.class) == null
                || runtimeContext.get(SandboxAcquireResult.class).getSandbox() == null) {
            throw new IllegalStateException("sandbox 档的文件与命令操作必须在绑定了会话沙箱的调用中执行");
        }
        return runtimeContext;
    }
}
