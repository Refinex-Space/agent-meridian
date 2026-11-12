package cn.refinex.meridian.controlplane.agent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.controlplane.support.AdminClient;
import cn.refinex.meridian.controlplane.support.AdminClient.Reply;
import cn.refinex.meridian.controlplane.support.ControlPlaneTestBase;
import cn.refinex.meridian.controlplane.support.TenantFixture;
import cn.refinex.meridian.controlplane.tenant.app.ConfigInvalidations;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import cn.refinex.meridian.persistence.redis.RedisMessaging;
import cn.refinex.meridian.testsupport.mcp.TestMcpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * 智能体配置引用的资源（T15b-A06、A08 的 control-plane 部分，ADR-0047）：环境的修订历史、技能包的上传与版本化（内置 docx 技能）、MCP 服务
 * 登记时的探测（凭据来自凭据库）、租户与项目策略及记住此决定、租户设置的内部读取，以及它们的 {@code config.invalidate}。
 */
class AgentResourcesIT extends ControlPlaneTestBase {

    /** 仓库中的内置 docx 技能（T12a），测试从模块目录读取。 */
    static final Path DOCX = Path.of("..", "skills", "builtin", "docx");

    @Autowired
    RedisMessaging redis;

    @Autowired
    ObjectStore objects;

    @Test
    void environmentChangesCreateRevisionsAndOldRevisionsStayReadable() throws InterruptedException {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client);
        String base = project(fixture) + "/environments";
        Reply created = client.admin(
                "POST",
                base,
                token("developer"),
                "{\"name\":\"分析\",\"tier\":\"sandbox\",\"size\":\"small\",\"egress_allowlist\":[\"PyPI.org\",\"*.pythonhosted.org\"]}");
        assertThat(created.status()).as(created.raw()).isEqualTo(201);
        String env = created.text("id");
        assertThat(created.json().get("revision").asLong()).isEqualTo(1);
        assertThat(created.json().get("egress_allowlist").toString())
                .isEqualTo("[\"pypi.org\",\"*.pythonhosted.org\"]");
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        try (var subscription = redis.subscribe(ConfigInvalidations.CHANNEL, messages::add)) {
            Thread.sleep(200); // 订阅在监听容器中异步生效
            Reply renamed = client.admin(
                    "PUT",
                    base + "/" + env,
                    token("developer"),
                    "{\"name\":\"分析二\",\"tier\":\"sandbox\",\"size\":\"small\",\"egress_allowlist\":[\"pypi.org\",\"*.pythonhosted.org\"]}");
            assertThat(renamed.json().get("revision").asLong()).as("只改名字不产生新修订").isEqualTo(1);
            Reply resized = client.admin(
                    "PUT",
                    base + "/" + env,
                    token("developer"),
                    "{\"name\":\"分析二\",\"tier\":\"sandbox\",\"size\":\"medium\",\"egress_allowlist\":[\"pypi.org\"]}");
            assertThat(resized.json().get("revision").asLong()).isEqualTo(2);
            assertThat(messages.poll(5, TimeUnit.SECONDS))
                    .isEqualTo("{\"type\":\"environment\",\"tenant_id\":\"" + fixture.get("tenant") + "\",\"id\":\""
                            + env + "\"}");
        }
        Reply history = client.admin("GET", base + "/" + env + "/revisions", token("viewer"), null);
        assertThat(history.json().get("data")).hasSize(2);
        assertThat(history.json().at("/data/0/size").asString()).isEqualTo("small");

        Reply current = client.internal("GET", "/internal/v1/environments/" + env, serviceToken(), null);
        Reply first = client.internal("GET", "/internal/v1/environments/" + env + "?revision=1", serviceToken(), null);
        assertThat(current.json().get("revision").asLong()).isEqualTo(2);
        assertThat(current.text("size")).isEqualTo("medium");
        assertThat(first.text("size")).isEqualTo("small");
        assertThat(first.text("project_id")).isEqualTo(fixture.get("project"));

        Reply invalid = client.admin(
                "POST",
                base,
                token("developer"),
                "{\"name\":\"坏\",\"tier\":\"none\",\"size\":\"small\",\"egress_allowlist\":[\"10.0.0.1\",\"a.com:443\"]}");
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(fields(invalid)).contains("size", "egress_allowlist.0", "egress_allowlist.1");

        assertThat(client.admin("DELETE", base + "/" + env, token("developer"), null)
                        .status())
                .isEqualTo(204);
        assertThat(client.internal("GET", "/internal/v1/environments/" + env, serviceToken(), null)
                        .text("code"))
                .isEqualTo("environment.not_found");
        assertThat(client.internal("GET", "/internal/v1/environments/" + env + "?revision=2", serviceToken(), null)
                        .status())
                .as("运行中的会话按固化的修订号继续读取")
                .isEqualTo(200);
    }

    @Test
    void builtinDocxSkillUploadsVersionsAndServesItsFilesFromObjectStorage() throws IOException {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client);
        String base = project(fixture) + "/skills";
        String skillMd = Files.readString(DOCX.resolve("SKILL.md"), StandardCharsets.UTF_8);
        String script = Files.readString(DOCX.resolve("scripts/md_to_docx.py"), StandardCharsets.UTF_8);

        Reply created = client.admin(
                "POST",
                base,
                token("developer"),
                skillPackage(Map.of("SKILL.md", skillMd, "scripts/md_to_docx.py", script)));
        assertThat(created.status()).as(created.raw()).isEqualTo(201);
        String skill = created.text("id");
        assertThat(created.text("name")).isEqualTo("docx");
        assertThat(created.json().get("latest_version").asInt()).isEqualTo(1);

        Reply second = client.admin(
                "POST",
                base + "/" + skill + "/versions",
                token("developer"),
                skillPackage(Map.of(
                        "SKILL.md",
                        skillMd.replace("# Markdown to Word", "# Markdown to Word (v2)"),
                        "scripts/md_to_docx.py",
                        script)));
        assertThat(second.status()).as(second.raw()).isEqualTo(201);
        assertThat(second.json().get("version").asInt()).isEqualTo(2);
        List<String> paths = new ArrayList<>();
        second.json().get("files").forEach(f -> paths.add(f.get("path").asString()));
        assertThat(paths).containsExactly("SKILL.md", "scripts/md_to_docx.py");
        assertThat(objects.get("cp/skills/" + fixture.get("tenant") + "/" + fixture.get("project") + "/" + skill
                        + "/v2/scripts/md_to_docx.py"))
                .hasValueSatisfying(bytes ->
                        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo(script));

        Reply content = client.internal("GET", "/internal/v1/skills/" + skill + "/versions/1", serviceToken(), null);
        assertThat(content.text("name")).isEqualTo("docx");
        assertThat(content.json().at("/files/SKILL.md").asString()).isEqualTo(skillMd);
        assertThat(content.json().at("/files/scripts~1md_to_docx.py").asString())
                .isEqualTo(script);

        Reply renamed = client.admin(
                "POST",
                base + "/" + skill + "/versions",
                token("developer"),
                skillPackage(Map.of("SKILL.md", skillMd.replace("name: docx", "name: pdf"))));
        Reply noFrontmatter =
                client.admin("POST", base, token("developer"), skillPackage(Map.of("SKILL.md", "# 没有 frontmatter")));
        Reply escaping = client.admin(
                "POST",
                base,
                token("developer"),
                skillPackage(Map.of("SKILL.md", skillMd.replace("name: docx", "name: other"), "../etc/passwd", "x")));
        Reply taken = client.admin("POST", base, token("developer"), skillPackage(Map.of("SKILL.md", skillMd)));
        assertThat(renamed.status()).isEqualTo(400);
        assertThat(noFrontmatter.status()).isEqualTo(400);
        assertThat(fields(escaping)).anySatisfy(f -> assertThat(f).endsWith(".path"));
        assertThat(taken.text("code")).isEqualTo("skill.name_taken");
    }

    @Test
    void mcpRegistrationProbesWithTheStoredCredentialAndSavesTheToolList() {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client).withAgentConfig(client);
        String base = project(fixture) + "/mcp-servers";
        int before = MCP.requests().size();

        Reply created = client.admin(
                "POST",
                base,
                token("developer"),
                "{\"name\":\"tickets\",\"transport\":\"sse\",\"url\":\"" + MCP.sseUrl()
                        + "\",\"header_secret_ids\":{\"Authorization\":\"" + fixture.get("mcp_secret")
                        + "\"},\"side_effect\":\"non_idempotent\",\"tool_side_effects\":{\"lookup_order\":\"none\"}}");
        assertThat(created.status()).as(created.raw()).isEqualTo(201);
        List<String> tools = new ArrayList<>();
        created.json()
                .get("tools")
                .forEach(t -> tools.add(
                        t.get("name").asString() + "=" + t.get("side_effect").asString()));
        assertThat(tools).containsExactly("create_ticket=non_idempotent", "lookup_order=none");
        assertThat(created.raw()).doesNotContain(MCP_TOKEN);
        List<TestMcpServer.Request> probes =
                MCP.requests().subList(before, MCP.requests().size());
        assertThat(probes)
                .isNotEmpty()
                .allSatisfy(r -> assertThat(r.authorization()).isEqualTo("Bearer " + MCP_TOKEN));

        Reply listed = client.admin("GET", base + "/" + created.text("id"), token("viewer"), null);
        assertThat(listed.json().get("tools")).hasSize(2);

        String wrongSecret = client.admin(
                        "POST",
                        "/admin/v1/tenants/" + fixture.get("tenant") + "/secrets",
                        token("owner"),
                        "{\"name\":\"错的令牌\",\"value\":\"Bearer wrong\"}")
                .text("id");
        Reply unauthorized = client.admin(
                "POST",
                base,
                token("developer"),
                TenantFixture.mcpServer("wrong", "streamable_http", MCP.streamableUrl(), wrongSecret));
        assertThat(unauthorized.status()).as(unauthorized.raw()).isEqualTo(400);
        assertThat(unauthorized.text("code")).isEqualTo("mcp_server.probe_failed");
        assertThat(unauthorized.raw()).doesNotContain("Bearer wrong");

        Reply metadata = client.admin(
                "POST",
                base,
                token("developer"),
                TenantFixture.mcpServer(
                        "meta", "streamable_http", "http://169.254.169.254/mcp", fixture.get("mcp_secret")));
        assertThat(fields(metadata)).containsExactly("url");
        Reply unknownTool = client.admin(
                "POST",
                base,
                token("developer"),
                "{\"name\":\"crm2\",\"transport\":\"streamable_http\",\"url\":\"" + MCP.streamableUrl()
                        + "\",\"header_secret_ids\":{\"Authorization\":\"" + fixture.get("mcp_secret")
                        + "\"},\"tool_side_effects\":{\"drop_tables\":\"none\"}}");
        assertThat(fields(unknownTool)).containsExactly("tool_side_effects.drop_tables");
    }

    @Test
    void policyLayersAndRememberedDecisionsAreServedToRuntime() throws InterruptedException {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client).withAgentConfig(client);
        String path = "/internal/v1/projects/" + fixture.get("project") + "/permission-rules";
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        Reply layers = client.internal("GET", path, serviceToken(), null);
        assertThat(layers.status()).as(layers.raw()).isEqualTo(200);
        assertThat(layers.json().at("/tenant_rules/0/tool_name").asString()).isEqualTo("web_fetch");
        assertThat(layers.json().at("/project_rules/0/rule_content").asString()).isEqualTo("docs/**");

        try (var subscription = redis.subscribe(ConfigInvalidations.CHANNEL, messages::add)) {
            Thread.sleep(200); // 订阅在监听容器中异步生效
            String remember = "{\"tool_name\":\"bash\",\"rule_content\":\"git status\",\"behavior\":\"allow\","
                    + "\"source_action_id\":\"act_01JC8Z7A7Q9X3M2N6P0R5T8W1Y\",\"decided_by\":\"key_1\"}";
            Reply first = client.internal("POST", path, serviceToken(), remember);
            Reply again = client.internal("POST", path, serviceToken(), remember);
            assertThat(first.status()).as(first.raw()).isEqualTo(200);
            assertThat(again.text("id")).as("相同的规则不重复写入").isEqualTo(first.text("id"));
            assertThat(first.text("source")).isEqualTo("remembered");
            assertThat(messages.poll(5, TimeUnit.SECONDS))
                    .isEqualTo("{\"type\":\"permission_policy\",\"tenant_id\":\"" + fixture.get("tenant")
                            + "\",\"id\":\"" + fixture.get("project") + "\"}");
        }
        Reply admin = client.admin("GET", project(fixture) + "/permission-rules", token("viewer"), null);
        List<String> sources = new ArrayList<>();
        admin.json()
                .get("data")
                .forEach(r -> sources.add(
                        r.get("tool_name").asString() + ":" + r.get("source").asString()));
        assertThat(sources).containsExactly("read_file:policy", "bash:remembered");
        assertThat(client.internal("GET", "/internal/v1/projects/prj_missing/permission-rules", serviceToken(), null)
                        .text("code"))
                .isEqualTo("project.not_found");
        Reply duplicate = client.admin(
                "POST",
                "/admin/v1/tenants/" + fixture.get("tenant") + "/permission-rules",
                token("owner"),
                "{\"tool_name\":\"web_fetch\",\"behavior\":\"ask\"}");
        assertThat(duplicate.text("code")).isEqualTo("permission_rule.already_exists");
    }

    @Test
    void tenantSettingsAreServedToRuntimeAndWebSearchCredentialsMustBeTenantLevel() throws InterruptedException {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client);
        String settings = "/admin/v1/tenants/" + fixture.get("tenant") + "/settings";
        String projectSecret = client.admin(
                        "POST",
                        "/admin/v1/tenants/" + fixture.get("tenant") + "/secrets",
                        token("owner"),
                        "{\"name\":\"项目凭据\",\"value\":\"v\",\"project_id\":\"" + fixture.get("project") + "\"}")
                .text("id");
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        Reply projectLevel = client.admin("PUT", settings, token("owner"), webSearch("提示", projectSecret));
        Reply missing = client.admin("PUT", settings, token("owner"), webSearch("提示", "sec_missing"));
        assertThat(fields(projectLevel)).containsExactly("web_search.secret_id");
        assertThat(fields(missing)).containsExactly("web_search.secret_id");

        try (var subscription = redis.subscribe(ConfigInvalidations.CHANNEL, messages::add)) {
            Thread.sleep(200); // 订阅在监听容器中异步生效
            Reply ok = client.admin("PUT", settings, token("owner"), webSearch("用中文回答", fixture.get("secret")));
            assertThat(ok.status()).as(ok.raw()).isEqualTo(200);
            assertThat(messages.poll(5, TimeUnit.SECONDS))
                    .isEqualTo("{\"type\":\"tenant_settings\",\"tenant_id\":\"" + fixture.get("tenant") + "\",\"id\":\""
                            + fixture.get("tenant") + "\"}");
        }
        Reply internal = client.internal(
                "GET", "/internal/v1/tenants/" + fixture.get("tenant") + "/settings", serviceToken(), null);
        assertThat(internal.text("system_prompt")).isEqualTo("用中文回答");
        assertThat(internal.json().at("/web_search/backend").asString()).isEqualTo("bocha");
        assertThat(internal.json().at("/web_search/secret_id").asString()).isEqualTo(fixture.get("secret"));
    }

    private static String webSearch(String prompt, String secretId) {
        return "{\"system_prompt\":\"" + prompt + "\",\"web_search\":{\"backend\":\"bocha\","
                + "\"base_url\":\"http://127.0.0.1:18099\",\"secret_id\":\"" + secretId + "\"}}";
    }

    private static String skillPackage(Map<String, String> files) {
        List<Map<String, String>> list = new ArrayList<>();
        new java.util.TreeMap<>(files).forEach((path, content) -> {
            Map<String, String> file = new LinkedHashMap<>();
            file.put("path", path);
            file.put("content", content);
            list.add(file);
        });
        return JsonMappers.lenient().writeValueAsString(Map.of("files", list));
    }

    private static List<String> fields(Reply reply) {
        assertThat(reply.status()).as(reply.raw()).isEqualTo(400);
        List<String> fields = new ArrayList<>();
        JsonNode errors = reply.json().get("errors");
        if (errors != null) {
            errors.forEach(e -> fields.add(e.get("field").asString()));
        }
        return fields;
    }

    private static String project(TenantFixture fixture) {
        return "/admin/v1/tenants/" + fixture.get("tenant") + "/projects/" + fixture.get("project");
    }
}
