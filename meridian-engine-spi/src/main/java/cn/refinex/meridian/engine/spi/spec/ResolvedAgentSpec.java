package cn.refinex.meridian.engine.spi.spec;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 运行时需要的、已解析的智能体版本配置，见架构设计「核心概念」「引擎装配」。
 *
 * <p>智能体版本发布后不可变，所以这个配置也不可变；内核按 {@code (agentVersionId, environmentId,
 * environmentRevision)} 缓存据此装配的智能体实例。它由 runtime 从 control-plane 的
 * {@code agent_versions.spec}（JSON）映射而来（T15b），字段与 JSON 的对应关系是 camelCase ↔ snake_case。
 *
 * @param agentId 智能体 ID（{@code agt_…}）；必填
 * @param agentVersionId 智能体版本 ID（{@code agv_…}）；必填，缓存键的一部分
 * @param name 智能体名，用作 AgentScope 智能体名与日志标识；必填
 * @param model 模型引用；必填
 * @param systemPrompt 智能体层的系统提示词（平台基础提示词与租户提示词由平台另行分层拼接，见「上下文管理」）；必填
 * @param tools 启用的平台工具名，取自 {@link PlatformToolNames#ALL}；可为空集合。{@code bash} 只在
 *     {@code sandbox} 档环境中生效
 * @param mcpServers 挂载的 MCP 服务；可为空列表，服务名不能重复
 * @param skills 可用技能；可为空列表
 * @param subagents 可拉起的子智能体；可为空列表，名字不能重复。非空时 {@code tools} 必须包含
 *     {@code spawn_subagent}
 * @param permissions 智能体版本的权限策略，作为五层合成中的智能体层；必填
 * @param compaction 压缩配置；必填
 * @param planMode 是否启用计划模式（{@code plan_enter}、{@code plan_write}、{@code plan_exit}）。这是构建期配置，
 *     无人值守会话靠权限规则禁止 {@code plan_enter}
 * @param knowledge 知识库绑定与前置检索；必填
 * @param maxIters 单轮最大推理次数，取值 [1, {@value #MAX_ITERS_LIMIT}]，默认 {@value #DEFAULT_MAX_ITERS}
 * @param exposeThinking 是否在 {@code agent.thinking} 事件中带出思考内容（架构设计「事件映射」）
 */
public record ResolvedAgentSpec(
        String agentId,
        String agentVersionId,
        String name,
        ModelSpec model,
        String systemPrompt,
        Set<String> tools,
        List<McpServerSpec> mcpServers,
        List<SkillRef> skills,
        List<SubagentSpec> subagents,
        PermissionPolicy permissions,
        CompactionSpec compaction,
        boolean planMode,
        KnowledgeSpec knowledge,
        int maxIters,
        boolean exposeThinking) {

    /** 单轮最大推理次数的默认值。架构设计「引擎装配」规定默认 50。 */
    public static final int DEFAULT_MAX_ITERS = 50;

    /** 单轮最大推理次数的上限。架构设计「引擎装配」规定上限 200，用于控制单轮成本。 */
    public static final int MAX_ITERS_LIMIT = 200;

    /**
     * 创建智能体版本配置。
     *
     * @throws NullPointerException 缺少必填字段时
     * @throws IllegalArgumentException 工具名未知、名字重复、子智能体与工具集不一致或轮数超出范围时
     */
    public ResolvedAgentSpec {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(agentVersionId, "agentVersionId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(compaction, "compaction");
        Objects.requireNonNull(knowledge, "knowledge");
        tools = tools == null ? Set.of() : Set.copyOf(tools);
        mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
        skills = skills == null ? List.of() : List.copyOf(skills);
        subagents = subagents == null ? List.of() : List.copyOf(subagents);
        checkMaxIters(maxIters);
        for (String tool : tools) {
            if (!PlatformToolNames.ALL.contains(tool)) {
                throw new IllegalArgumentException("未知的平台工具：" + tool);
            }
        }
        checkUnique(mcpServers.stream().map(McpServerSpec::name).toList(), "MCP 服务名");
        checkUnique(subagents.stream().map(SubagentSpec::name).toList(), "子智能体名");
        if (!subagents.isEmpty() && !tools.contains(PlatformToolNames.SPAWN_SUBAGENT)) {
            throw new IllegalArgumentException("声明了子智能体时，工具集必须包含 spawn_subagent");
        }
        for (SubagentSpec subagent : subagents) {
            for (String tool : subagent.tools()) {
                if (!grants(tools, mcpServers, tool)) {
                    throw new IllegalArgumentException("子智能体 " + subagent.name() + " 的工具 " + tool + " 不在父智能体的工具集内");
                }
            }
        }
    }

    /**
     * 判断父智能体的工具集是否包含某个工具：平台工具看 {@code tools}，MCP 工具看服务是否挂载、该工具是否启用。
     */
    private static boolean grants(Set<String> tools, List<McpServerSpec> mcpServers, String tool) {
        if (!PlatformToolNames.isMcpTool(tool)) {
            return tools.contains(tool);
        }
        String rest = tool.substring(PlatformToolNames.MCP_PREFIX.length());
        int separator = rest.indexOf("__");
        if (separator <= 0) {
            return false;
        }
        String server = rest.substring(0, separator);
        String serverTool = rest.substring(separator + 2);
        return mcpServers.stream()
                .anyMatch(m -> m.name().equals(server)
                        && (m.enabledTools().isEmpty() || m.enabledTools().contains(serverTool)));
    }

    /**
     * 判断智能体的工具集是否包含某个工具：平台工具看 {@code tools}，MCP 工具（{@code mcp__{server}__{tool}}）看服务是否挂载、
     * 该工具是否启用。{@code PolicyMiddleware} 据此做纵深防御（架构设计「平台中间件」）。
     *
     * @param toolName 工具名
     * @return 包含时为 true
     */
    public boolean grants(String toolName) {
        return grants(tools, mcpServers, toolName);
    }

    /**
     * 按名字查找子智能体。
     *
     * @param subagentName 子智能体名
     * @return 子智能体配置，不存在时为空
     */
    public Optional<SubagentSpec> subagent(String subagentName) {
        return subagents.stream().filter(s -> s.name().equals(subagentName)).findFirst();
    }

    /**
     * 校验单轮最大推理次数的取值范围，子智能体配置共用。
     *
     * @param maxIters 最大推理次数
     * @throws IllegalArgumentException 超出 [1, {@value #MAX_ITERS_LIMIT}] 时
     */
    static void checkMaxIters(int maxIters) {
        if (maxIters < 1 || maxIters > MAX_ITERS_LIMIT) {
            throw new IllegalArgumentException("maxIters 必须在 [1, " + MAX_ITERS_LIMIT + "] 内：" + maxIters);
        }
    }

    private static void checkUnique(List<String> names, String what) {
        Set<String> seen = new HashSet<>();
        for (String n : names) {
            if (!seen.add(n)) {
                throw new IllegalArgumentException(what + "重复：" + n);
            }
        }
    }
}
