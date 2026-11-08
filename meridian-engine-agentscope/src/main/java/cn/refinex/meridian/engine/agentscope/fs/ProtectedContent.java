package cn.refinex.meridian.engine.agentscope.fs;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.engine.agentscope.assembly.AgentProfile;
import cn.refinex.meridian.engine.spi.spec.McpServerSpec;
import cn.refinex.meridian.engine.spi.spec.SkillDefinition;
import cn.refinex.meridian.engine.spi.spec.SubagentSpec;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 受保护路径的内容，由平台按装配对象（智能体版本或子智能体）生成，对智能体只读（架构设计「文件系统装配」）：
 *
 * <ul>
 *   <li>{@code AGENTS.md}：智能体层的系统提示词。
 *   <li>{@code skills/<name>/}：可用技能的 {@code SKILL.md} 与附带资源；技能清单与 {@code load_skill_through_path} 都从这里读。
 *   <li>{@code subagents/<name>.md}：可拉起的子智能体（frontmatter 为名字、说明、工具、轮数，正文为提示词）；子智能体自己没有。
 *   <li>{@code tools.json}：启用的平台工具与挂载的 MCP 服务（只是说明，harness 不读取它，见 {@code disableToolsConfig}）。
 *   <li>{@code MEMORY.md}：一期为空。
 * </ul>
 *
 * <p>智能体版本发布后不可变，所以内容在装配时生成一次，随智能体实例缓存。
 *
 * @param agentsMd {@code AGENTS.md} 的内容
 * @param skills 技能目录下的文件：{@code <name>/<相对路径>} → 内容
 * @param subagents 子智能体目录下的文件：{@code <name>.md} → 内容
 * @param toolsJson {@code tools.json} 的内容
 * @param memoryMd {@code MEMORY.md} 的内容
 */
public record ProtectedContent(
        String agentsMd, Map<String, String> skills, Map<String, String> subagents, String toolsJson, String memoryMd) {

    /** {@code AGENTS.md} 的路由（单文件）。 */
    public static final String AGENTS_MD = "/AGENTS.md";

    /** 技能目录的路由。 */
    public static final String SKILLS = "/skills/";

    /** 子智能体目录的路由。 */
    public static final String SUBAGENTS = "/subagents/";

    /** {@code tools.json} 的路由（单文件）。 */
    public static final String TOOLS_JSON = "/tools.json";

    /** {@code MEMORY.md} 的路由（单文件）。 */
    public static final String MEMORY_MD = "/MEMORY.md";

    /** 全部受保护路径的路由前缀，与 ADR-0031 平台安全规则中的写入拒绝一一对应。 */
    public static final List<String> ROUTES = List.of(AGENTS_MD, SKILLS, SUBAGENTS, TOOLS_JSON, MEMORY_MD);

    /** 创建内容，复制映射。 */
    public ProtectedContent {
        skills = Map.copyOf(skills);
        subagents = Map.copyOf(subagents);
    }

    /**
     * 按装配对象与已解析的技能生成内容。
     *
     * @param profile 装配对象
     * @param skillDefinitions 装配对象可用的技能内容，按 {@link AgentProfile#skills()} 的顺序
     * @return 内容
     */
    public static ProtectedContent of(AgentProfile profile, List<SkillDefinition> skillDefinitions) {
        Map<String, String> skills = new TreeMap<>();
        for (SkillDefinition skill : skillDefinitions) {
            skill.files().forEach((path, content) -> skills.put(skill.name() + "/" + path, content));
        }
        Map<String, String> subagents = new TreeMap<>();
        if (!profile.isSubagent()) {
            for (SubagentSpec subagent : profile.agent().subagents()) {
                subagents.put(subagent.name() + ".md", subagentMarkdown(subagent));
            }
        }
        return new ProtectedContent(profile.systemPrompt(), skills, subagents, toolsJson(profile), "");
    }

    /**
     * 每个受保护路径的只读文件系统，作为 {@code CompositeFilesystem} 的路由表。目录路由的后端路径已剥离前缀，单文件路由
     * 保留原路径（{@code CompositeFilesystem} 的约定）。
     *
     * @return 路由前缀 → 只读文件系统，按 {@link #ROUTES} 的顺序
     */
    public Map<String, AbstractFilesystem> routes() {
        Map<String, AbstractFilesystem> routes = new LinkedHashMap<>();
        routes.put(AGENTS_MD, ReadOnlyFilesystem.of(Map.of(AGENTS_MD, agentsMd)));
        routes.put(SKILLS, skillsFilesystem());
        routes.put(SUBAGENTS, ReadOnlyFilesystem.of(prefixed(subagents)));
        routes.put(TOOLS_JSON, ReadOnlyFilesystem.of(Map.of(TOOLS_JSON, toolsJson)));
        routes.put(MEMORY_MD, ReadOnlyFilesystem.of(Map.of(MEMORY_MD, memoryMd)));
        return routes;
    }

    /**
     * 技能目录的只读视图（后端路径 {@code /<name>/SKILL.md}），技能仓库在它上面读取技能。
     *
     * @return 文件系统
     */
    public ReadOnlyFilesystem skillsFilesystem() {
        return ReadOnlyFilesystem.of(prefixed(skills));
    }

    private static Map<String, String> prefixed(Map<String, String> files) {
        Map<String, String> result = new TreeMap<>();
        files.forEach((path, content) -> result.put("/" + path, content));
        return result;
    }

    private static String subagentMarkdown(SubagentSpec subagent) {
        StringBuilder md = new StringBuilder("---\n");
        md.append("name: ").append(subagent.name()).append('\n');
        md.append("description: ")
                .append(JsonMappers.strict().writeValueAsString(subagent.description()))
                .append('\n');
        md.append("tools: ")
                .append(JsonMappers.strict().writeValueAsString(new TreeSet<>(subagent.tools())))
                .append('\n');
        md.append("max_iters: ").append(subagent.maxIters()).append('\n');
        md.append("---\n\n").append(subagent.systemPrompt());
        return md.toString();
    }

    private static String toolsJson(AgentProfile profile) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("tools", new TreeSet<>(profile.tools()));
        if (!profile.isSubagent()) {
            document.put(
                    "mcp_servers",
                    profile.agent().mcpServers().stream()
                            .map(ProtectedContent::mcpServer)
                            .toList());
        }
        return JsonMappers.strict().writerWithDefaultPrettyPrinter().writeValueAsString(document);
    }

    private static Map<String, Object> mcpServer(McpServerSpec server) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", server.name());
        entry.put("enabled_tools", new TreeSet<>(server.enabledTools()));
        return entry;
    }
}
