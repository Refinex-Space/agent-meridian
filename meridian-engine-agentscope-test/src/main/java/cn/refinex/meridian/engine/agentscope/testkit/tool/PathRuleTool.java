package cn.refinex.meridian.engine.agentscope.testkit.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Mono;

/**
 * 实现了 {@code matchRule} 的测试工具，用来验证内容级权限规则与审批流程（架构设计「平台工具」「权限判定」，P6）。
 *
 * <p>入参只有一个路径 {@code path}；规则内容是相对工作区根的路径 glob，语义与平台文件工具约定的相同：{@code **} 匹配零或多级
 * 目录，所以 {@code **}{@code /.env} 也匹配根目录的 {@code .env}。可以指定一组路径，工具自检对它们返回安全类 ask
 * （{@code decisionReason} 含 {@code safety}），这种 ask 不受规则与模式影响（AgentScope 2.0.4 {@code PermissionEngine} 第 3 步）。
 *
 * <p>线程安全：调用记录用写时复制列表保存。
 */
public final class PathRuleTool extends ToolBase {

    /** 只有一个字符串参数 {@code path} 的入参 schema。 */
    public static final Map<String, Object> PATH_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("path", Map.of("type", "string", "description", "相对工作区根的路径")),
            "required", List.of("path"));

    private final Set<String> safetyAskPaths;
    private final List<ToolCallParam> calls = new CopyOnWriteArrayList<>();

    private PathRuleTool(String name, boolean readOnly, Set<String> safetyAskPaths) {
        super(ToolBase.builder()
                .name(name)
                .description("按路径操作的测试工具 " + name)
                .inputSchema(PATH_SCHEMA)
                .readOnly(readOnly)
                .concurrencySafe(true));
        this.safetyAskPaths = Set.copyOf(safetyAskPaths);
    }

    /**
     * 有写副作用的路径工具（例如模拟 {@code write_file}）。
     *
     * @param name 工具名
     * @return 工具
     */
    public static PathRuleTool writing(String name) {
        return new PathRuleTool(name, false, Set.of());
    }

    /**
     * 有写副作用、并对给定路径做安全类自检 ask 的路径工具。
     *
     * @param name 工具名
     * @param safetyAskPaths 工具自检返回安全类 ask 的路径
     * @return 工具
     */
    public static PathRuleTool withSafetyAsk(String name, Set<String> safetyAskPaths) {
        return new PathRuleTool(name, false, safetyAskPaths);
    }

    @Override
    public boolean matchRule(String ruleContent, Map<String, Object> toolInput) {
        if (ruleContent == null) {
            return true;
        }
        Object path = toolInput.get("path");
        return path instanceof String text && matches(ruleContent, text);
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput, PermissionContextState context) {
        Object path = toolInput.get("path");
        if (path instanceof String text && safetyAskPaths.contains(text)) {
            return Mono.just(PermissionDecision.builder()
                    .behavior(io.agentscope.core.permission.PermissionBehavior.ASK)
                    .message("路径 " + text + " 需要确认")
                    .decisionReason("safety check: sensitive path " + text)
                    .build());
        }
        return super.checkPermissions(toolInput, context);
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        calls.add(param);
        return Mono.fromSupplier(
                () -> ToolResultBlock.text(getName() + " 完成：" + param.getInput().get("path")));
    }

    /**
     * 已执行的调用，按调用顺序；被拒绝的调用不在其中。
     *
     * @return 调用参数
     */
    public List<ToolCallParam> calls() {
        return List.copyOf(calls);
    }

    /**
     * 已执行调用的路径。
     *
     * @return 路径
     */
    public List<String> calledPaths() {
        return calls.stream().map(c -> String.valueOf(c.getInput().get("path"))).toList();
    }

    /** 路径 glob 匹配：{@code **}{@code /} 前缀同时匹配零级目录。 */
    static boolean matches(String glob, String path) {
        Path candidate = Path.of(path);
        if (FileSystems.getDefault().getPathMatcher("glob:" + glob).matches(candidate)) {
            return true;
        }
        return glob.startsWith("**/") && matches(glob.substring(3), path);
    }
}
