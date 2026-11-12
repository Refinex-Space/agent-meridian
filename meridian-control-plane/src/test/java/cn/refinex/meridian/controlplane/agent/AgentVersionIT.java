package cn.refinex.meridian.controlplane.agent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.controlplane.support.AdminClient;
import cn.refinex.meridian.controlplane.support.AdminClient.Reply;
import cn.refinex.meridian.controlplane.support.ControlPlaneTestBase;
import cn.refinex.meridian.controlplane.support.TenantFixture;
import cn.refinex.meridian.controlplane.tenant.app.ConfigInvalidations;
import cn.refinex.meridian.persistence.redis.RedisMessaging;
import cn.refinex.meridian.security.servicejwt.ServiceJwtVerifier;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import cn.refinex.meridian.security.servicejwt.ServicePrincipal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * 智能体版本（T15b-A02、A03、A07 的公开接口部分，ADR-0047）：发布时的六项校验各有失败用例，发布后不可变，版本对比，内部解析给出快照与
 * 技能内容，公开接口只给出本项目已发布的智能体，删除被已发布版本引用的资源被拒绝，发布与删除经 {@code config.invalidate} 广播。
 */
class AgentVersionIT extends ControlPlaneTestBase {

    @Autowired
    RedisMessaging redis;

    @Test
    void publishRejectsEachOfTheSixReferenceAndToolsetViolationsWithFieldErrors() {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client).withAgentConfig(client);
        String agent = agent(client, fixture, "checks");

        // 1. 模型引用存在：不存在的模型、不是对话模型的模型
        assertPublishFails(
                client,
                fixture,
                agent,
                spec(fixture, "\"model\":{\"model_id\":\"mdl_missing\"}", ""),
                "spec.model.model_id");
        // 2. 工具集合法：未知的平台工具在草稿上就被 Schema 拒绝；声明了子智能体却没有 spawn_subagent
        Reply unknownTool = client.admin(
                "POST",
                versions(fixture, agent),
                token("developer"),
                fixture.fill("{\"spec\":" + spec(fixture, null, "\"tools\":[\"shell_execute\"]") + "}"));
        assertValidation(unknownTool, "spec.tools.0");
        assertPublishFails(
                client,
                fixture,
                agent,
                spec(fixture, null, "\"tools\":[\"read_file\"],\"subagents\":[" + subagent("[\"read_file\"]") + "]"),
                "spec.tools");
        // 3. 子智能体的工具集是父智能体的子集：平台工具与未挂载的 MCP 工具
        assertPublishFails(
                client,
                fixture,
                agent,
                spec(
                        fixture,
                        null,
                        "\"tools\":[\"read_file\",\"spawn_subagent\"],\"subagents\":["
                                + subagent("[\"web_fetch\",\"mcp__crm__lookup_order\"]") + "]"),
                "spec.subagents.0.tools.0",
                "spec.subagents.0.tools.1");
        // 4. 知识库存在且属于本项目（经 knowledge 的内部接口，ADR-0051）：另一个项目的知识库与不存在的相同
        String knowledgeBase = KNOWLEDGE.register(fixture.get("tenant"), fixture.get("project"));
        String otherProjectBase = KNOWLEDGE.register(fixture.get("tenant"), fixture.get("other_project"));
        assertPublishFails(
                client,
                fixture,
                agent,
                spec(
                        fixture,
                        null,
                        "\"knowledge\":{\"knowledge_base_ids\":[\"" + knowledgeBase
                                + "\",\"kb_missing\",\"" + otherProjectBase
                                + "\"],\"prefetch\":true,\"prefetch_top_k\":3}"),
                "spec.knowledge.knowledge_base_ids.1",
                "spec.knowledge.knowledge_base_ids.2");
        // 5. MCP 服务存在、启用的工具在探测到的清单中
        assertPublishFails(
                client,
                fixture,
                agent,
                spec(fixture, null, "\"mcp_servers\":[{\"mcp_server_id\":\"mcp_missing\",\"enabled_tools\":[]}]"),
                "spec.mcp_servers.0.mcp_server_id");
        assertPublishFails(
                client,
                fixture,
                agent,
                spec(
                        fixture,
                        null,
                        "\"mcp_servers\":[{\"mcp_server_id\":\"" + fixture.get("mcp")
                                + "\",\"enabled_tools\":[\"drop_tables\"]}]"),
                "spec.mcp_servers.0.enabled_tools.0");
        // 6. 技能版本存在
        assertPublishFails(
                client,
                fixture,
                agent,
                spec(fixture, null, "\"skills\":[{\"skill_id\":\"" + fixture.get("skill") + "\",\"version\":9}]"),
                "spec.skills.0");

        // 全部满足时发布成功：子智能体用父智能体挂载并启用的 MCP 工具，知识库存在
        Reply ok = draft(
                client,
                fixture,
                agent,
                spec(
                        fixture,
                        null,
                        "\"tools\":[\"read_file\",\"spawn_subagent\"],\"mcp_servers\":[{\"mcp_server_id\":\""
                                + fixture.get("mcp")
                                + "\",\"enabled_tools\":[\"lookup_order\"]}],\"subagents\":["
                                + subagent("[\"read_file\",\"mcp__crm__lookup_order\"]")
                                + "],\"knowledge\":{\"knowledge_base_ids\":[\"" + knowledgeBase
                                + "\"],\"prefetch\":true,\"prefetch_top_k\":3}"));
        Reply published =
                publish(client, fixture, agent, ok.json().get("version").asInt());
        assertThat(published.status()).as(published.raw()).isEqualTo(200);
        assertThat(published.json().at("/spec/subagents/0/tools").toString()).contains("mcp__crm__lookup_order");
    }

    @Test
    void knowledgeBasesAreCheckedThroughTheKnowledgeServiceWithAControlPlaneServiceJwt() throws Exception {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client).withAgentConfig(client);
        String agent = agent(client, fixture, "knowledge-check");
        String knowledgeBase = KNOWLEDGE.register(fixture.get("tenant"), fixture.get("project"));
        String withKnowledge = "\"knowledge\":{\"knowledge_base_ids\":[\"" + knowledgeBase
                + "\"],\"prefetch\":false,\"prefetch_top_k\":3}";

        Reply ok = draft(client, fixture, agent, spec(fixture, null, withKnowledge));
        int before = KNOWLEDGE.authorizations().size();
        assertThat(publish(client, fixture, agent, ok.json().get("version").asInt())
                        .status())
                .isEqualTo(200);

        // 出站令牌由 control-plane 在进程内签发：sub 是 control-plane，aud 是 knowledge，用本服务的 JWKS 能校验
        String authorization = KNOWLEDGE.authorizations().get(before);
        assertThat(authorization).startsWith("Bearer ");
        ServiceJwtVerifier verifier = ServiceJwtVerifier.remote(
                URI.create("http://127.0.0.1:" + port + ServiceJwts.JWKS_PATH).toURL(),
                ServiceJwts.DEFAULT_ISSUER,
                ServiceJwts.KNOWLEDGE,
                Duration.ofMinutes(5));
        assertThat(verifier.resolve(authorization.substring("Bearer ".length())))
                .contains(new ServicePrincipal(ServiceJwts.CONTROL_PLANE));

        // knowledge 不可用时发布被拒绝（503），不把"查不到"当成"不存在"
        Reply next = draft(client, fixture, agent, spec(fixture, null, withKnowledge));
        KNOWLEDGE.down(true);
        try {
            Reply unavailable =
                    publish(client, fixture, agent, next.json().get("version").asInt());
            assertThat(unavailable.status()).isEqualTo(503);
            assertThat(unavailable.text("code")).isEqualTo("service.unavailable");
        } finally {
            KNOWLEDGE.down(false);
        }
    }

    @Test
    void publishedVersionsAreImmutableAndTheComparisonListsTheChanges() throws InterruptedException {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client).withAgentConfig(client);
        String agent = fixture.get("agent");
        String version1 = versions(fixture, agent) + "/1";
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        Reply modify = client.admin("PUT", version1, token("developer"), fixture.fill(TenantFixture.VERSION));
        Reply delete = client.admin("DELETE", version1, token("developer"), null);
        Reply again = client.admin("POST", version1 + ":publish", token("developer"), null);
        assertThat(List.of(modify, delete, again)).allSatisfy(reply -> {
            assertThat(reply.status()).isEqualTo(409);
            assertThat(reply.text("code")).isEqualTo("agent.version_published");
        });

        // 草稿可以修改：换系统提示词、多一个工具、去掉技能
        String changed = TenantFixture.VERSION
                .replace("\"system_prompt\":\"夹具智能体\"", "\"system_prompt\":\"新的提示词\"")
                .replace("\"tools\":[\"read_file\"]", "\"tools\":[\"read_file\",\"web_fetch\"]")
                .replace("\"skills\":[{\"skill_id\":\"{skill}\",\"version\":1}]", "\"skills\":[]");
        Reply draft = client.admin("PUT", versions(fixture, agent) + "/2", token("developer"), fixture.fill(changed));
        assertThat(draft.status()).as(draft.raw()).isEqualTo(200);
        assertThat(draft.text("status")).isEqualTo("draft");

        Reply compare =
                client.admin("GET", versions(fixture, agent) + ":compare?base=1&target=2", token("viewer"), null);
        assertThat(compare.status()).as(compare.raw()).isEqualTo(200);
        List<String> changes = new ArrayList<>();
        compare.json()
                .get("changes")
                .forEach(c -> changes.add(
                        c.get("kind").asString() + " " + c.get("path").asString()));
        // 草稿还没有发布时填写的快照字段：智能体 ID、版本 ID、名字、MCP 服务的端点等出现为删除
        assertThat(changes)
                .contains(
                        "changed /system_prompt",
                        "added /tools/1",
                        "removed /skills/0",
                        "removed /agent_version_id",
                        "removed /mcp_servers/0/url");
        JsonNode prompt = find(compare.json().get("changes"), "/system_prompt");
        assertThat(prompt.get("before").asString()).isEqualTo("夹具智能体");
        assertThat(prompt.get("after").asString()).isEqualTo("新的提示词");

        try (var subscription = redis.subscribe(ConfigInvalidations.CHANNEL, messages::add)) {
            Thread.sleep(200); // 订阅在监听容器中异步生效
            Reply published = publish(client, fixture, agent, 2);
            assertThat(published.status()).as(published.raw()).isEqualTo(200);
            assertThat(messages.poll(5, TimeUnit.SECONDS))
                    .isEqualTo("{\"type\":\"agent\",\"tenant_id\":\"" + fixture.get("tenant") + "\",\"id\":\"" + agent
                            + "\"}");
        }
        Reply sameAfterPublish =
                client.admin("GET", versions(fixture, agent) + ":compare?base=2&target=2", token("viewer"), null);
        assertThat(sameAfterPublish.json().get("changes")).isEmpty();
    }

    @Test
    void resolveReturnsThePublishedSnapshotWithSkillContentAndOnlyToRuntime() {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client).withAgentConfig(client);
        String agent = fixture.get("agent");
        String versionId = client.admin("GET", versions(fixture, agent) + "/1", token("viewer"), null)
                .text("id");
        String draftId = client.admin("GET", versions(fixture, agent) + "/2", token("viewer"), null)
                .text("id");

        Reply byId =
                client.internal("POST", "/internal/v1/agent-versions/" + versionId + ":resolve", serviceToken(), null);
        Reply current = client.internal("POST", "/internal/v1/agents/" + agent + ":resolve", serviceToken(), null);

        assertThat(byId.status()).as(byId.raw()).isEqualTo(200);
        assertThat(current.json().get("agent_version_id").asString()).isEqualTo(versionId);
        JsonNode spec = byId.json().get("spec");
        assertThat(byId.text("tenant_id")).isEqualTo(fixture.get("tenant"));
        assertThat(byId.text("project_id")).isEqualTo(fixture.get("project"));
        assertThat(byId.json().get("agent_deleted").asBoolean()).isFalse();
        assertThat(spec.get("agent_id").asString()).isEqualTo(agent);
        assertThat(spec.get("agent_version_id").asString()).isEqualTo(versionId);
        assertThat(spec.get("name").asString()).isEqualTo("fixture-agent");
        JsonNode mcp = spec.at("/mcp_servers/0");
        assertThat(mcp.get("name").asString()).isEqualTo("crm");
        assertThat(mcp.get("url").asString()).isEqualTo(MCP.streamableUrl());
        assertThat(mcp.get("transport").asString()).isEqualTo("streamable_http");
        assertThat(mcp.at("/header_secret_ids/Authorization").asString()).isEqualTo(fixture.get("mcp_secret"));
        assertThat(spec.at("/skills/0/name").asString()).isEqualTo(TenantFixture.SKILL_NAME);
        assertThat(byId.raw()).as("凭据只以 ID 出现").doesNotContain(MCP_TOKEN);
        JsonNode skill = byId.json().at("/skills/0");
        assertThat(skill.get("skill_id").asString()).isEqualTo(fixture.get("skill"));
        assertThat(skill.at("/files/SKILL.md").asString()).startsWith("---\nname: " + TenantFixture.SKILL_NAME);

        assertThat(client.internal("POST", "/internal/v1/agent-versions/" + draftId + ":resolve", serviceToken(), null)
                        .text("code"))
                .isEqualTo("agent.version_not_found");
        Reply unauthenticated =
                client.internal("POST", "/internal/v1/agent-versions/" + versionId + ":resolve", null, null);
        assertThat(unauthenticated.status()).isEqualTo(401);

        // 删除智能体：按智能体解析 404，按版本仍可解析（运行中的会话）且标为已删除
        assertThat(client.admin("DELETE", agentPath(fixture, agent), token("developer"), null)
                        .status())
                .isEqualTo(204);
        assertThat(client.internal("POST", "/internal/v1/agents/" + agent + ":resolve", serviceToken(), null)
                        .text("code"))
                .isEqualTo("agent.not_found");
        assertThat(client.internal(
                                "POST", "/internal/v1/agent-versions/" + versionId + ":resolve", serviceToken(), null)
                        .json()
                        .get("agent_deleted")
                        .asBoolean())
                .isTrue();
    }

    @Test
    void publicApiListsOnlyThisProjectsPublishedAgentsForSessionReaders() {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client).withAgentConfig(client);
        String published = fixture.get("agent");
        String draftOnly = agent(client, fixture, "draft-only");
        draft(client, fixture, draftOnly, spec(fixture, null, ""));
        String otherProject = client.admin(
                        "POST",
                        "/admin/v1/tenants/" + fixture.get("tenant") + "/projects/" + fixture.get("other_project")
                                + "/agents",
                        token("owner"),
                        "{\"name\":\"other\"}")
                .text("id");
        String key = fixture.get("key_plaintext");

        Reply list = client.api("GET", "/v1/agents", key, null);
        assertThat(list.status()).as(list.raw()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        list.json().get("data").forEach(a -> ids.add(a.get("id").asString()));
        assertThat(ids).containsExactly(published).doesNotContain(draftOnly, otherProject);
        assertThat(list.json().at("/data/0/current_version/version").asInt()).isEqualTo(1);
        assertThat(list.header("RateLimit-Limit")).as("按 Key 限流（D55）").isNotNull();

        assertThat(client.api("GET", "/v1/agents/" + published, key, null).text("name"))
                .isEqualTo("fixture-agent");
        assertThat(client.api("GET", "/v1/agents/" + draftOnly, key, null).text("code"))
                .isEqualTo("agent.not_found");
        assertThat(client.api("GET", "/v1/agents/" + otherProject, key, null).status())
                .isEqualTo(404);
        assertThat(client.api("GET", "/v1/agents", "mk_live_wrong", null).text("code"))
                .isEqualTo("auth.invalid_api_key");

        // 控制台用户带 Meridian-Project：项目开发者有 sessions:read；没有角色的项目 403
        Reply console = client.api("GET", "/v1/agents", token("developer"), fixture.get("project"));
        assertThat(console.status()).as(console.raw()).isEqualTo(200);
        assertThat(client.api("GET", "/v1/agents", token("developer"), fixture.get("other_project"))
                        .status())
                .isEqualTo(403);

        String writeOnly = client.admin(
                        "POST",
                        "/admin/v1/tenants/" + fixture.get("tenant") + "/projects/" + fixture.get("project")
                                + "/api-keys",
                        token("owner"),
                        "{\"name\":\"只写\",\"scopes\":[\"sessions:write\"]}")
                .text("key");
        assertThat(client.api("GET", "/v1/agents", writeOnly, null).text("code"))
                .isEqualTo("auth.insufficient_scope");
    }

    @Test
    void resourcesReferencedByPublishedVersionsCannotBeDeleted() {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client).withAgentConfig(client);
        String tenant = "/admin/v1/tenants/" + fixture.get("tenant");
        String project = tenant + "/projects/" + fixture.get("project");

        Reply model = client.admin("DELETE", tenant + "/models/" + fixture.get("model"), token("owner"), null);
        Reply skill = client.admin("DELETE", project + "/skills/" + fixture.get("skill"), token("owner"), null);
        Reply mcp = client.admin("DELETE", project + "/mcp-servers/" + fixture.get("mcp"), token("owner"), null);
        Reply secret = client.admin("DELETE", tenant + "/secrets/" + fixture.get("mcp_secret"), token("owner"), null);

        assertThat(model.text("code")).isEqualTo("catalog.in_use");
        assertThat(skill.text("code")).isEqualTo("skill.in_use");
        assertThat(mcp.text("code")).isEqualTo("mcp_server.in_use");
        assertThat(secret.text("code")).isEqualTo("secret.in_use");
        for (Reply reply : List.of(model, skill, mcp)) {
            assertThat(reply.json().at("/references/0/type").asString()).isEqualTo("agent_version");
            assertThat(reply.json().at("/references/0/name").asString()).isEqualTo("fixture-agent v1");
        }
        List<String> secretRefs = new ArrayList<>();
        secret.json()
                .get("references")
                .forEach(r -> secretRefs.add(r.get("type").asString()));
        assertThat(secretRefs).contains("mcp_server", "agent_version");

        // 智能体删除后，它的版本不再算引用方
        assertThat(client.admin("DELETE", agentPath(fixture, fixture.get("agent")), token("owner"), null)
                        .status())
                .isEqualTo(204);
        assertThat(client.admin("DELETE", project + "/skills/" + fixture.get("skill"), token("owner"), null)
                        .status())
                .isEqualTo(204);
    }

    private static void assertPublishFails(
            AdminClient client, TenantFixture fixture, String agent, String spec, String... fields) {
        Reply created = draft(client, fixture, agent, spec);
        Reply published =
                publish(client, fixture, agent, created.json().get("version").asInt());
        assertValidation(published, fields);
        Reply version = client.admin(
                "GET",
                versions(fixture, agent) + "/" + created.json().get("version").asInt(),
                token("viewer"),
                null);
        assertThat(version.text("status")).as("校验失败时仍是草稿").isEqualTo("draft");
    }

    private static void assertValidation(Reply reply, String... fields) {
        assertThat(reply.status()).as(reply.raw()).isEqualTo(400);
        assertThat(reply.text("code")).isEqualTo("request.validation_failed");
        List<String> reported = new ArrayList<>();
        reply.json().get("errors").forEach(e -> reported.add(e.get("field").asString()));
        assertThat(reported).as(reply.raw()).contains(fields);
    }

    private static Reply draft(AdminClient client, TenantFixture fixture, String agent, String spec) {
        Reply reply = client.admin(
                "POST", versions(fixture, agent), token("developer"), fixture.fill("{\"spec\":" + spec + "}"));
        assertThat(reply.status()).as(reply.raw()).isEqualTo(201);
        return reply;
    }

    private static Reply publish(AdminClient client, TenantFixture fixture, String agent, int version) {
        return client.admin("POST", versions(fixture, agent) + "/" + version + ":publish", token("developer"), null);
    }

    private static String agent(AdminClient client, TenantFixture fixture, String name) {
        Reply reply = client.admin(
                "POST", projectPath(fixture) + "/agents", token("developer"), "{\"name\":\"" + name + "\"}");
        assertThat(reply.status()).as(reply.raw()).isEqualTo(201);
        return reply.text("id");
    }

    /**
     * 一份规格：默认用夹具模型、只有 {@code read_file}、没有 MCP 服务与技能；{@code model} 替换模型字段，{@code extra} 中的字段覆盖默认值
     * （后出现的同名字段生效）。
     */
    private static String spec(TenantFixture fixture, String model, String extra) {
        String base = "{" + (model == null ? "\"model\":{\"model_id\":\"" + fixture.get("model") + "\"}" : model)
                + ",\"system_prompt\":\"校验\",\"tools\":[\"read_file\"],\"mcp_servers\":[],\"skills\":[],\"subagents\":[],"
                + "\"permissions\":{\"mode\":\"default\",\"rules\":[]},\"compaction\":{\"reserved_tokens\":20000},"
                + "\"plan_mode\":false,\"knowledge\":{\"knowledge_base_ids\":[],\"prefetch\":false,\"prefetch_top_k\":5},"
                + "\"max_iters\":50,\"expose_thinking\":false";
        return base + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private static String subagent(String tools) {
        return "{\"name\":\"reader\",\"description\":\"读资料\",\"model\":{\"model_id\":\"{model}\"},\"system_prompt\":\"只读\","
                + "\"tools\":" + tools + ",\"skills\":[],\"permissions\":{\"mode\":\"default\",\"rules\":[]},"
                + "\"max_iters\":10,\"timeout\":\"PT2M\"}";
    }

    private static String projectPath(TenantFixture fixture) {
        return "/admin/v1/tenants/" + fixture.get("tenant") + "/projects/" + fixture.get("project");
    }

    private static String agentPath(TenantFixture fixture, String agent) {
        return projectPath(fixture) + "/agents/" + agent;
    }

    private static String versions(TenantFixture fixture, String agent) {
        return agentPath(fixture, agent) + "/versions";
    }

    private static JsonNode find(JsonNode changes, String path) {
        for (JsonNode change : changes) {
            if (change.get("path").asString().equals(path)) {
                return change;
            }
        }
        throw new AssertionError("没有路径 " + path + " 的差异：" + changes);
    }
}
