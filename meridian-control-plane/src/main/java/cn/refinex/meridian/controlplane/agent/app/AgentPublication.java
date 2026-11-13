package cn.refinex.meridian.controlplane.agent.app;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.controlplane.agent.domain.Agent;
import cn.refinex.meridian.controlplane.agent.domain.AgentSpecRules;
import cn.refinex.meridian.controlplane.agent.domain.AgentVersion;
import cn.refinex.meridian.controlplane.agent.domain.SpecReference;
import cn.refinex.meridian.controlplane.catalog.domain.CatalogModel;
import cn.refinex.meridian.controlplane.catalog.domain.ModelKind;
import cn.refinex.meridian.controlplane.catalog.infra.ModelRepository;
import cn.refinex.meridian.controlplane.mcp.domain.McpServer;
import cn.refinex.meridian.controlplane.mcp.domain.McpTool;
import cn.refinex.meridian.controlplane.mcp.infra.McpServerRepository;
import cn.refinex.meridian.controlplane.skill.domain.SkillVersion;
import cn.refinex.meridian.controlplane.skill.infra.SkillRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 发布智能体版本前的校验与快照（ADR-0047）。在发布事务中、绑定了项目的 {@code PlatformContext} 下调用：
 *
 * <ol>
 *   <li>草稿按 JSON Schema 校验结构（工具名在平台工具清单中、名字格式、取值范围、子智能体不能有 {@code spawn_subagent}）；
 *   <li>写入快照：智能体 ID、版本 ID、智能体名；每个 MCP 服务的名字、端点、传输、请求头凭据与副作用等级（服务必须存在，启用的工具必须在
 *       探测到的清单中）；每个技能引用的技能名（技能版本必须存在）；
 *   <li>模型（含备用模型、子智能体的模型）存在于本租户的目录且是对话模型；知识库存在（{@link KnowledgeCatalogClient}）；
 *   <li>写入快照后的规格再按 JSON Schema 与 {@link AgentSpecRules} 校验（名字不重复、子智能体的工具集是父智能体的子集等）。
 * </ol>
 *
 * <p>被引用的 MCP 服务、技能以共享锁读取，模型以排他锁读取：并发的删除要等发布提交，提交后删除会看到新的引用而拒绝。任何一步不通过都以
 * {@code request.validation_failed} 失败，字段错误是 {@code spec.} 加 JSON 路径。
 */
@Component
public class AgentPublication {

    private final AgentSpecSchema schema;
    private final McpServerRepository mcpServers;
    private final SkillRepository skills;
    private final ModelRepository models;
    private final KnowledgeCatalogClient knowledge;

    /**
     * 创建发布步骤。
     *
     * @param schema 规格的 JSON Schema
     * @param mcpServers MCP 服务仓储（项目级）
     * @param skills 技能仓储（项目级）
     * @param models 模型目录（租户级）
     * @param knowledge 知识库目录
     */
    public AgentPublication(
            AgentSpecSchema schema,
            McpServerRepository mcpServers,
            SkillRepository skills,
            ModelRepository models,
            KnowledgeCatalogClient knowledge) {
        this.schema = schema;
        this.mcpServers = mcpServers;
        this.skills = skills;
        this.models = models;
        this.knowledge = knowledge;
    }

    /**
     * 校验并写入快照。
     *
     * @param agent 智能体
     * @param draft 要发布的草稿
     * @return 快照后的规格与它引用的资源
     * @throws ValidationException 任何一项校验不通过时
     */
    public Prepared prepare(Agent agent, AgentVersion draft) {
        requireValid(schema.validate(draft.spec()));
        ObjectNode spec = (ObjectNode) JsonMappers.lenient().readTree(draft.spec());
        spec.put("agent_id", agent.id());
        spec.put("agent_version_id", draft.id());
        spec.put("name", agent.name());
        List<FieldViolation> violations = new ArrayList<>();
        snapshotMcpServers(spec, violations);
        snapshotSkills(spec.path("skills"), "spec.skills", violations);
        JsonNode subagents = spec.path("subagents");
        for (int i = 0; i < subagents.size(); i++) {
            snapshotSkills(subagents.get(i).path("skills"), "spec.subagents." + i + ".skills", violations);
        }
        checkModel(spec.path("model"), "spec.model", violations);
        for (int i = 0; i < subagents.size(); i++) {
            checkModel(subagents.get(i).path("model"), "spec.subagents." + i + ".model", violations);
        }
        checkModelId(
                spec.path("knowledge").path("query_rewrite_model"), "spec.knowledge.query_rewrite_model", violations);
        JsonNode knowledgeBases = spec.path("knowledge").path("knowledge_base_ids");
        for (int i = 0; i < knowledgeBases.size(); i++) {
            String id = knowledgeBases.get(i).asString();
            if (!knowledge.exists(agent.tenantId(), agent.projectId(), id)) {
                violations.add(new FieldViolation("spec.knowledge.knowledge_base_ids." + i, "知识库不存在：" + id));
            }
        }
        requireValid(violations);
        String json = JsonMappers.lenient().writeValueAsString(spec);
        List<FieldViolation> complete = new ArrayList<>(schema.validate(json));
        complete.addAll(AgentSpecRules.check(spec));
        requireValid(complete);
        return new Prepared(json, SpecReference.of(spec));
    }

    /** 每个挂载的 MCP 服务写入登记时的配置；启用的工具必须在探测到的清单中。 */
    private void snapshotMcpServers(ObjectNode spec, List<FieldViolation> violations) {
        JsonNode servers = spec.path("mcp_servers");
        for (int i = 0; i < servers.size(); i++) {
            ObjectNode node = (ObjectNode) servers.get(i);
            String path = "spec.mcp_servers." + i;
            String id = node.path("mcp_server_id").asString();
            Optional<McpServer> found = mcpServers.find(id, "SHARE");
            if (found.isEmpty()) {
                violations.add(new FieldViolation(path + ".mcp_server_id", "MCP 服务不存在：" + id));
                continue;
            }
            McpServer server = found.get();
            node.put("name", server.name());
            node.put("url", server.url());
            node.put("transport", server.transport());
            node.set("header_secret_ids", JsonMappers.lenient().valueToTree(server.headerSecretIds()));
            if (server.sideEffect() == null) {
                node.putNull("side_effect");
            } else {
                node.put("side_effect", server.sideEffect());
            }
            node.set("tool_side_effects", JsonMappers.lenient().valueToTree(server.toolSideEffects()));
            Set<String> available = server.tools().stream().map(McpTool::name).collect(Collectors.toSet());
            JsonNode enabled = node.path("enabled_tools");
            for (int j = 0; j < enabled.size(); j++) {
                if (!available.contains(enabled.get(j).asString())) {
                    violations.add(new FieldViolation(
                            path + ".enabled_tools." + j,
                            "MCP 服务 " + server.name() + " 的工具清单中没有 "
                                    + enabled.get(j).asString()));
                }
            }
        }
    }

    /** 每个技能引用写入技能名；技能（未删除）与版本必须存在。 */
    private void snapshotSkills(JsonNode refs, String path, List<FieldViolation> violations) {
        for (int i = 0; i < refs.size(); i++) {
            ObjectNode node = (ObjectNode) refs.get(i);
            String skillId = node.path("skill_id").asString();
            int version = node.path("version").asInt();
            Optional<SkillVersion> found = skills.find(skillId, "SHARE").flatMap(s -> skills.version(skillId, version));
            if (found.isEmpty()) {
                violations.add(new FieldViolation(path + "." + i, "技能版本不存在：" + skillId + " v" + version));
            } else {
                node.put("name", found.get().name());
            }
        }
    }

    /** 主模型与备用模型存在于本租户的目录，且是对话模型；查询改写模型同样要求（T19）。 */
    private void checkModel(JsonNode model, String path, List<FieldViolation> violations) {
        checkModelId(model.path("model_id"), path + ".model_id", violations);
        checkModelId(model.path("fallback_model_id"), path + ".fallback_model_id", violations);
    }

    private void checkModelId(JsonNode id, String field, List<FieldViolation> violations) {
        if (!id.isString()) {
            return;
        }
        Optional<CatalogModel> model = models.find(id.asString(), true);
        if (model.isEmpty()) {
            violations.add(new FieldViolation(field, "模型不存在：" + id.asString()));
        } else if (model.get().kind() != ModelKind.CHAT) {
            violations.add(new FieldViolation(field, "不是对话模型：" + id.asString()));
        }
    }

    private static void requireValid(List<FieldViolation> violations) {
        if (!violations.isEmpty()) {
            throw new ValidationException(CommonErrorCode.VALIDATION_FAILED, "智能体规格不合法", violations);
        }
    }

    /**
     * 发布用的规格。
     *
     * @param spec 写入快照后的规格 JSON
     * @param references 它引用的资源
     */
    public record Prepared(String spec, Set<SpecReference> references) {}
}
