package cn.refinex.meridian.controlplane.agent.domain;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * 已发布版本引用的一个资源（含子智能体的），写进 {@code cp.agent_version_refs}，删除被引用的资源时据此列出引用方（ADR-0047）。
 *
 * @param type 资源类型
 * @param id 资源 ID
 */
public record SpecReference(Type type, String id) {

    /**
     * 创建引用。
     *
     * @throws NullPointerException 缺少类型或 ID 时
     */
    public SpecReference {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
    }

    /**
     * 列出规格引用的全部资源：主智能体与子智能体的模型（含备用模型）与技能，MCP 服务及其请求头凭据，知识库。
     *
     * @param spec 规格（已写入发布快照）
     * @return 引用，去重，按出现顺序
     */
    public static Set<SpecReference> of(JsonNode spec) {
        Set<SpecReference> refs = new LinkedHashSet<>();
        models(spec.path("model"), refs);
        skills(spec.path("skills"), refs);
        for (JsonNode server : spec.path("mcp_servers")) {
            add(refs, Type.MCP_SERVER, server.path("mcp_server_id"));
            for (JsonNode secret : server.path("header_secret_ids")) {
                add(refs, Type.SECRET, secret);
            }
        }
        for (JsonNode kb : spec.path("knowledge").path("knowledge_base_ids")) {
            add(refs, Type.KNOWLEDGE_BASE, kb);
        }
        for (JsonNode subagent : spec.path("subagents")) {
            models(subagent.path("model"), refs);
            skills(subagent.path("skills"), refs);
        }
        return refs;
    }

    private static void models(JsonNode model, Set<SpecReference> refs) {
        add(refs, Type.MODEL, model.path("model_id"));
        add(refs, Type.MODEL, model.path("fallback_model_id"));
    }

    private static void skills(JsonNode skills, Set<SpecReference> refs) {
        for (JsonNode skill : skills) {
            add(refs, Type.SKILL, skill.path("skill_id"));
        }
    }

    private static void add(Set<SpecReference> refs, Type type, JsonNode id) {
        if (id.isString()) {
            refs.add(new SpecReference(type, id.asString()));
        }
    }

    /** 被引用资源的类型，取值与 {@code agent_version_refs.ref_type} 相同。 */
    public enum Type {
        /** 模型。 */
        MODEL("model"),
        /** 技能。 */
        SKILL("skill"),
        /** MCP 服务。 */
        MCP_SERVER("mcp_server"),
        /** 凭据（MCP 服务的请求头）。 */
        SECRET("secret"),
        /** 知识库。 */
        KNOWLEDGE_BASE("knowledge_base");

        private final String value;

        Type(String value) {
            this.value = value;
        }

        /**
         * 返回库中的取值。
         *
         * @return 小写取值
         */
        public String value() {
            return value;
        }
    }
}
