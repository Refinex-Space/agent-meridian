package cn.refinex.meridian.controlplane.support;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.controlplane.support.AdminClient.Reply;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个经管理 API 建好的租户：所有者 {@code owner}、第二位所有者 {@code owner2}、管理员 {@code admin}，项目 {@code project} 上的开发者、
 * 观察者、审批人，另一个项目 {@code other_project}，可被移除的成员 {@code spare}（{@code project} 的观察者），{@code project} 下的
 * 一个 API Key，以及凭据与模型目录（T15a）。
 *
 * @param values 占位符到 ID 的映射：tenant、project、other_project、spare_member、spare_binding、owner2_member、owner2_binding、
 *     key、key_plaintext、secret、spare_secret、provider、spare_provider、price_book、model、spare_model
 */
public record TenantFixture(Map<String, String> values) {

    /**
     * 建一个新租户。
     *
     * @param client 客户端
     * @return 夹具
     */
    public static TenantFixture create(AdminClient client) {
        Map<String, String> v = new LinkedHashMap<>();
        Reply tenant = ok(client.admin(
                "POST",
                "/admin/v1/tenants",
                ControlPlaneTestBase.token(ControlPlaneTestBase.PLATFORM),
                "{\"name\":\"夹具租户\",\"owner\":{\"subject\":\"owner\",\"email\":\"owner@example.com\"}}"));
        String t = tenant.text("id");
        v.put("tenant", t);
        String owner = ControlPlaneTestBase.token("owner");
        String base = "/admin/v1/tenants/" + t;
        v.put(
                "project",
                ok(client.admin("POST", base + "/projects", owner, "{\"name\":\"P\"}"))
                        .text("id"));
        v.put(
                "other_project",
                ok(client.admin("POST", base + "/projects", owner, "{\"name\":\"Q\"}"))
                        .text("id"));
        Map<String, String> members = new LinkedHashMap<>();
        for (String subject : new String[] {"admin", "developer", "viewer", "approver", "owner2", "spare"}) {
            members.put(
                    subject,
                    ok(client.admin("POST", base + "/members", owner, "{\"subject\":\"" + subject + "\"}"))
                            .text("id"));
        }
        bind(client, base, owner, members.get("admin"), "tenant_admin", null);
        bind(client, base, owner, members.get("developer"), "project_developer", v.get("project"));
        bind(client, base, owner, members.get("viewer"), "project_viewer", v.get("project"));
        bind(client, base, owner, members.get("approver"), "approver", v.get("project"));
        v.put("owner2_member", members.get("owner2"));
        v.put("owner2_binding", bind(client, base, owner, members.get("owner2"), "tenant_owner", null));
        v.put("spare_member", members.get("spare"));
        v.put("spare_binding", bind(client, base, owner, members.get("spare"), "project_viewer", v.get("project")));
        Reply key = ok(client.admin(
                "POST",
                base + "/projects/" + v.get("project") + "/api-keys",
                owner,
                "{\"name\":\"夹具 Key\",\"scopes\":[\"sessions:read\",\"sessions:write\"]}"));
        v.put("key", key.text("id"));
        v.put("key_plaintext", key.text("key"));
        catalog(client, base, owner, v);
        // T16：租户级预算（不限），角色矩阵的预算行引用它
        v.put(
                "budget",
                ok(client.admin("POST", base + "/budgets", owner, "{\"level\":\"tenant\",\"hard_limit_tokens\":null}"))
                        .text("id"));
        return new TenantFixture(Map.copyOf(v));
    }

    /**
     * 取一个值。
     *
     * @param name 占位符名
     * @return ID
     */
    public String get(String name) {
        return values.get(name);
    }

    /**
     * 替换文本中的占位符。
     *
     * @param text 含 {@code {名称}} 的文本；可为空
     * @return 替换后的文本
     */
    public String fill(String text) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    /**
     * 凭据与模型目录（T15a）：被供应商账号引用的凭据 {@code secret}、没有引用方的 {@code spare_secret}；挂着模型的供应商账号
     * {@code provider} 与空账号 {@code spare_provider}；价格表 {@code price_book}；模型 {@code model} 与没有引用方的 {@code spare_model}。
     * 端点在回环地址上（测试配置放行回环），不做 DNS 解析。
     */
    private static void catalog(AdminClient client, String base, String owner, Map<String, String> v) {
        v.put(
                "secret",
                ok(client.admin(
                                "POST",
                                base + "/secrets",
                                owner,
                                "{\"name\":\"夹具凭据\",\"value\":\"fixture-secret-value\"}"))
                        .text("id"));
        v.put(
                "spare_secret",
                ok(client.admin(
                                "POST",
                                base + "/secrets",
                                owner,
                                "{\"name\":\"备用凭据\",\"value\":\"spare-secret-value\"}"))
                        .text("id"));
        v.put(
                "provider",
                ok(client.admin(
                                "POST",
                                base + "/provider-accounts",
                                owner,
                                "{\"name\":\"夹具账号\",\"provider\":\"openai_compatible\","
                                        + "\"base_url\":\"http://127.0.0.1:18090/v1\",\"secret_id\":\""
                                        + v.get("secret") + "\"}"))
                        .text("id"));
        v.put(
                "spare_provider",
                ok(client.admin(
                                "POST",
                                base + "/provider-accounts",
                                owner,
                                "{\"name\":\"空账号\",\"provider\":\"anthropic\"}"))
                        .text("id"));
        v.put(
                "price_book",
                ok(client.admin(
                                "POST",
                                base + "/price-books",
                                owner,
                                "{\"name\":\"夹具价格表\",\"currency\":\"CNY\"," + PRICES + "}"))
                        .text("id"));
        v.put(
                "model",
                ok(client.admin("POST", base + "/models", owner, model(v.get("provider"), "夹具模型", v.get("price_book"))))
                        .text("id"));
        v.put(
                "spare_model",
                ok(client.admin("POST", base + "/models", owner, model(v.get("provider"), "备用模型", null)))
                        .text("id"));
    }

    /**
     * 加上智能体配置的资源（T15b，ADR-0047）：请求头凭据 {@code mcp_secret}（值为 {@code Bearer} 加进程内 MCP 服务的令牌）；登记的 MCP
     * 服务 {@code mcp}（Streamable HTTP，被已发布的版本引用）与 {@code spare_mcp}（SSE，没有引用方）；环境 {@code environment}；技能
     * {@code skill}（{@value #SKILL_NAME}，被引用）与 {@code spare_skill}；智能体 {@code agent}：版本 1 已发布（引用 {@code model}、
     * {@code mcp}、{@code skill}），版本 2 是草稿；租户策略规则 {@code tenant_rule} 与项目策略规则 {@code project_rule}；另有
     * {@code mcp_url}。
     *
     * @param client 客户端
     * @return 加上这些资源的夹具
     */
    public TenantFixture withAgentConfig(AdminClient client) {
        Map<String, String> v = new LinkedHashMap<>(values);
        String owner = ControlPlaneTestBase.token("owner");
        String base = "/admin/v1/tenants/" + v.get("tenant");
        String project = base + "/projects/" + v.get("project");
        v.put("mcp_url", ControlPlaneTestBase.MCP.streamableUrl());
        v.put(
                "mcp_secret",
                ok(client.admin(
                                "POST",
                                base + "/secrets",
                                owner,
                                "{\"name\":\"MCP 令牌\",\"value\":\"Bearer " + ControlPlaneTestBase.MCP_TOKEN + "\"}"))
                        .text("id"));
        v.put(
                "mcp",
                ok(client.admin(
                                "POST",
                                project + "/mcp-servers",
                                owner,
                                mcpServer(
                                        "crm",
                                        "streamable_http",
                                        ControlPlaneTestBase.MCP.streamableUrl(),
                                        v.get("mcp_secret"))))
                        .text("id"));
        v.put(
                "spare_mcp",
                ok(client.admin(
                                "POST",
                                project + "/mcp-servers",
                                owner,
                                mcpServer("crm_sse", "sse", ControlPlaneTestBase.MCP.sseUrl(), v.get("mcp_secret"))))
                        .text("id"));
        v.put(
                "environment",
                ok(client.admin("POST", project + "/environments", owner, "{\"name\":\"夹具环境\",\"tier\":\"none\"}"))
                        .text("id"));
        v.put(
                "skill",
                ok(client.admin("POST", project + "/skills", owner, skillPackage(SKILL_NAME, "夹具技能")))
                        .text("id"));
        v.put(
                "spare_skill",
                ok(client.admin("POST", project + "/skills", owner, skillPackage("spare-skill", "备用技能")))
                        .text("id"));
        String agent = ok(client.admin("POST", project + "/agents", owner, "{\"name\":\"fixture-agent\"}"))
                .text("id");
        v.put("agent", agent);
        TenantFixture filled = new TenantFixture(Map.copyOf(v));
        ok(client.admin("POST", project + "/agents/" + agent + "/versions", owner, filled.fill(VERSION)));
        ok(client.admin("POST", project + "/agents/" + agent + "/versions/1:publish", owner, null));
        ok(client.admin("POST", project + "/agents/" + agent + "/versions", owner, filled.fill(VERSION)));
        v.put(
                "tenant_rule",
                ok(client.admin(
                                "POST",
                                base + "/permission-rules",
                                owner,
                                "{\"tool_name\":\"web_fetch\",\"behavior\":\"ask\"}"))
                        .text("id"));
        v.put(
                "project_rule",
                ok(client.admin(
                                "POST",
                                project + "/permission-rules",
                                owner,
                                "{\"tool_name\":\"read_file\",\"rule_content\":\"docs/**\",\"behavior\":\"allow\"}"))
                        .text("id"));
        return new TenantFixture(Map.copyOf(v));
    }

    /** 夹具技能的名字（frontmatter 的 {@code name}）。 */
    public static final String SKILL_NAME = "fixture-skill";

    /**
     * 夹具智能体的版本请求体：引用夹具模型、MCP 服务的 {@code lookup_order} 与夹具技能，放行 {@code read_file}。占位符经 {@link #fill}
     * 替换。
     */
    public static final String VERSION = """
            {"spec":{"model":{"model_id":"{model}"},"system_prompt":"夹具智能体","tools":["read_file"],
             "mcp_servers":[{"mcp_server_id":"{mcp}","enabled_tools":["lookup_order"]}],
             "skills":[{"skill_id":"{skill}","version":1}],"subagents":[],
             "permissions":{"mode":"default","rules":[{"tool_name":"read_file","behavior":"allow","layer":"agent_version"}]},
             "compaction":{"reserved_tokens":20000},"plan_mode":false,
             "knowledge":{"knowledge_base_ids":[],"prefetch":false,"prefetch_top_k":5},"max_iters":50,"expose_thinking":false}}
            """;

    /**
     * 登记 MCP 服务的请求体。
     *
     * @param name 服务名
     * @param transport 传输
     * @param url 端点
     * @param secretId {@code Authorization} 头的凭据
     * @return JSON
     */
    public static String mcpServer(String name, String transport, String url, String secretId) {
        return "{\"name\":\"" + name + "\",\"transport\":\"" + transport + "\",\"url\":\"" + url
                + "\",\"header_secret_ids\":{\"Authorization\":\"" + secretId + "\"}}";
    }

    /**
     * 只有 {@code SKILL.md} 的技能包请求体。
     *
     * @param name frontmatter 的 {@code name}
     * @param description frontmatter 的 {@code description}
     * @return JSON
     */
    public static String skillPackage(String name, String description) {
        return "{\"files\":[{\"path\":\"SKILL.md\",\"content\":\"---\\nname: " + name + "\\ndescription: " + description
                + "\\n---\\n# " + name + "\\n\"}]}";
    }

    /** 价格表请求中的单价字段。 */
    public static final String PRICES =
            "\"prices\":{\"input\":\"0.8\",\"output\":\"2\",\"cache_read\":\"0.16\",\"cache_write\":\"0.8\",\"reasoning\":\"8\"}";

    /**
     * 创建对话模型的请求体。
     *
     * @param providerAccountId 供应商账号
     * @param name 名称
     * @param priceBookId 价格表；可为空
     * @return JSON
     */
    public static String model(String providerAccountId, String name, String priceBookId) {
        return "{\"provider_account_id\":\"" + providerAccountId + "\",\"name\":\"" + name + "\",\"kind\":\"chat\","
                + "\"upstream_model\":\"scripted\",\"context_window\":131072,\"max_output_tokens\":2048"
                + (priceBookId == null ? "" : ",\"price_book_id\":\"" + priceBookId + "\"") + "}";
    }

    private static String bind(
            AdminClient client, String base, String token, String memberId, String role, String projectId) {
        String body = "{\"member_id\":\"" + memberId + "\",\"role\":\"" + role + "\""
                + (projectId == null ? "" : ",\"project_id\":\"" + projectId + "\"") + "}";
        return ok(client.admin("POST", base + "/role-bindings", token, body)).text("id");
    }

    private static Reply ok(Reply reply) {
        assertThat(reply.status()).as(reply.raw()).isBetween(200, 299);
        return reply;
    }
}
