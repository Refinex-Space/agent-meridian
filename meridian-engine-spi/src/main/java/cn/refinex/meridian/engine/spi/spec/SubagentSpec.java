package cn.refinex.meridian.engine.spi.spec;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 智能体版本中声明的一个子智能体，见架构设计「子智能体」。
 *
 * <p>子智能体由 {@code spawn_subagent} 同步拉起，有自己的模型、提示词与工具集，工具集必须是父智能体的子集；
 * 它不能再拉起子智能体（嵌套深度固定为 1），也不挂载 steer 与前置检索中间件。子智能体的智能体实例
 * 按父智能体版本加子智能体名单独装配和缓存。
 *
 * @param name 子智能体名，即 {@code spawn_subagent} 的 {@code subagent} 参数，匹配 {@code ^[a-z][a-z0-9_-]{0,63}$}；必填
 * @param description 给父智能体看的用途说明；必填
 * @param model 模型引用；必填
 * @param systemPrompt 系统提示词；必填
 * @param tools 可用工具名（平台工具名或 {@code mcp__…} 全名），必须是父智能体工具集的子集，且不含
 *     {@code spawn_subagent}；可为空集合
 * @param skills 可用技能；可为空列表
 * @param permissions 子智能体版本自带的权限策略，作为合成时的智能体层；必填
 * @param maxIters 单次执行的最大推理轮数，取值 [1, {@value ResolvedAgentSpec#MAX_ITERS_LIMIT}]
 * @param timeout 单次执行的超时，取值 (0, 600 秒]，超时后取消子智能体的订阅并返回超时结果
 */
public record SubagentSpec(
        String name,
        String description,
        ModelSpec model,
        String systemPrompt,
        Set<String> tools,
        List<SkillRef> skills,
        PermissionPolicy permissions,
        int maxIters,
        Duration timeout) {

    /** 子智能体执行超时的上限。架构设计「子智能体」规定 600 秒。 */
    public static final Duration MAX_TIMEOUT = Duration.ofSeconds(600);

    /** 子智能体名的格式。 */
    public static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_-]{0,63}$");

    /**
     * 创建子智能体配置。
     *
     * @throws NullPointerException 缺少必填字段时
     * @throws IllegalArgumentException 名字、工具集、轮数或超时不合法时
     */
    public SubagentSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(timeout, "timeout");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("子智能体名不合法：" + name);
        }
        tools = tools == null ? Set.of() : Set.copyOf(tools);
        if (tools.contains(PlatformToolNames.SPAWN_SUBAGENT)) {
            throw new IllegalArgumentException("子智能体不能再拉起子智能体：" + name);
        }
        skills = skills == null ? List.of() : List.copyOf(skills);
        ResolvedAgentSpec.checkMaxIters(maxIters);
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("子智能体超时必须在 (0, 600 秒] 内：" + timeout);
        }
    }
}
