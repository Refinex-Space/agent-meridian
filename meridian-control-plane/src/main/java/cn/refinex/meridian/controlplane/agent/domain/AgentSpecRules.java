package cn.refinex.meridian.controlplane.agent.domain;

import cn.refinex.meridian.common.error.FieldViolation;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * 智能体规格中 JSON Schema 表达不了的规则，与 engine-spi 的 {@code ResolvedAgentSpec}、{@code SubagentSpec}、{@code KnowledgeSpec}
 * 构造时的校验一致（ADR-0047）：发布时在写入快照之后检查，runtime 收到的规格因此一定能构造出来。
 *
 * <ul>
 *   <li>MCP 服务、子智能体、技能（同一智能体内）的名字不重复，同一个 MCP 服务不挂两次；
 *   <li>声明了子智能体时，工具集包含 {@code spawn_subagent}；
 *   <li>子智能体的工具是父智能体工具集的子集：平台工具在父智能体的 {@code tools} 中，MCP 工具的服务已挂载且该工具启用；
 *   <li>子智能体的超时在 (0, 600 秒]；
 *   <li>开启前置检索时至少绑定一个知识库。
 * </ul>
 *
 * <p>字段名是 {@code spec.} 加点号连接的路径，例如 {@code spec.subagents.0.tools.1}，与管理接口的 {@code errors} 一致。
 */
public final class AgentSpecRules {

    /** 平台工具 {@code spawn_subagent} 的名字。 */
    public static final String SPAWN_SUBAGENT = "spawn_subagent";

    /** MCP 工具名的前缀：{@code mcp__{server}__{tool}}。 */
    public static final String MCP_PREFIX = "mcp__";

    /** 子智能体执行超时的上限，架构设计「子智能体」规定 600 秒。 */
    public static final Duration MAX_SUBAGENT_TIMEOUT = Duration.ofSeconds(600);

    private AgentSpecRules() {}

    /**
     * 检查规格。
     *
     * @param spec 已写入发布快照、通过了 JSON Schema 结构校验的规格
     * @return 违反的规则；没有时为空列表
     */
    public static List<FieldViolation> check(JsonNode spec) {
        List<FieldViolation> violations = new ArrayList<>();
        unique(spec.path("mcp_servers"), "mcp_server_id", "spec.mcp_servers", "MCP 服务重复挂载", violations);
        unique(spec.path("mcp_servers"), "name", "spec.mcp_servers", "MCP 服务名重复", violations);
        unique(spec.path("subagents"), "name", "spec.subagents", "子智能体名重复", violations);
        unique(spec.path("skills"), "name", "spec.skills", "技能名重复", violations);
        Set<String> tools = strings(spec.path("tools"));
        JsonNode subagents = spec.path("subagents");
        if (!subagents.isEmpty() && !tools.contains(SPAWN_SUBAGENT)) {
            violations.add(new FieldViolation("spec.tools", "声明了子智能体时，工具集必须包含 spawn_subagent"));
        }
        for (int i = 0; i < subagents.size(); i++) {
            JsonNode subagent = subagents.get(i);
            String path = "spec.subagents." + i;
            unique(subagent.path("skills"), "name", path + ".skills", "技能名重复", violations);
            JsonNode subTools = subagent.path("tools");
            for (int j = 0; j < subTools.size(); j++) {
                String tool = subTools.get(j).asString();
                if (!grants(spec, tools, tool)) {
                    violations.add(new FieldViolation(path + ".tools." + j, "子智能体的工具 " + tool + " 不在父智能体的工具集内"));
                }
            }
            timeout(subagent.path("timeout"), path + ".timeout", violations);
        }
        JsonNode knowledge = spec.path("knowledge");
        if (knowledge.path("prefetch").asBoolean(false)
                && knowledge.path("knowledge_base_ids").isEmpty()) {
            violations.add(new FieldViolation("spec.knowledge.knowledge_base_ids", "开启前置检索时必须绑定至少一个知识库"));
        }
        return List.copyOf(violations);
    }

    /**
     * 父智能体的工具集是否包含某个工具：平台工具看 {@code tools}，MCP 工具看服务是否挂载、该工具是否启用（空的启用清单表示全部）。
     */
    private static boolean grants(JsonNode spec, Set<String> tools, String tool) {
        if (!tool.startsWith(MCP_PREFIX)) {
            return tools.contains(tool);
        }
        String rest = tool.substring(MCP_PREFIX.length());
        int separator = rest.indexOf("__");
        if (separator <= 0) {
            return false;
        }
        String server = rest.substring(0, separator);
        String serverTool = rest.substring(separator + 2);
        for (JsonNode mounted : spec.path("mcp_servers")) {
            if (server.equals(mounted.path("name").asString(null))) {
                Set<String> enabled = strings(mounted.path("enabled_tools"));
                return enabled.isEmpty() || enabled.contains(serverTool);
            }
        }
        return false;
    }

    private static void timeout(JsonNode value, String field, List<FieldViolation> violations) {
        try {
            Duration timeout = Duration.parse(value.asString(""));
            if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_SUBAGENT_TIMEOUT) > 0) {
                violations.add(new FieldViolation(field, "子智能体超时必须在 (0, 600 秒] 内"));
            }
        } catch (DateTimeParseException e) {
            violations.add(new FieldViolation(field, "不是 ISO 8601 时长"));
        }
    }

    private static void unique(JsonNode items, String property, String path, String message, List<FieldViolation> out) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            JsonNode value = items.get(i).path(property);
            if (value.isString() && !seen.add(value.asString())) {
                out.add(new FieldViolation(path + "." + i + "." + property, message + "：" + value.asString()));
            }
        }
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> values = new HashSet<>();
        for (JsonNode item : array) {
            if (item.isString()) {
                values.add(item.asString());
            }
        }
        return values;
    }
}
