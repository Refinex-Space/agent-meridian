package cn.refinex.meridian.knowledge.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.knowledge.support.KnowledgeTestBase;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/**
 * MCP Server {@code /mcp/knowledge}（T19，P8）：MCP SDK 0.17.2 的客户端经 Streamable HTTP 初始化、发现并调用 {@code knowledge_search}；
 * API Key 认证与作用域，访问标签取请求头 {@code Meridian-Acl-Tags}（A02 的 MCP 形态），检索计量记为 {@code mcp}。
 */
class KnowledgeMcpIT extends KnowledgeTestBase {

    @LocalServerPort
    private int port;

    @Test
    void knowledgeSearchIsDiscoveredAndOnlyReturnsChunksTheCallerMaySee() {
        Tenant tenant = newTenant();
        String base = createKnowledgeBase(tenant);
        ingest(tenant, base, "attendance.md", KnowledgeSearchIT.evalCorpus("attendance.md"), null);
        ingest(tenant, base, "compensation.md", KnowledgeSearchIT.evalCorpus("compensation.md"), "level:confidential");

        try (McpSyncClient anonymous = client(tenant.key(), null)) {
            McpSchema.InitializeResult init = anonymous.initialize();
            assertThat(init.serverInfo().name()).isEqualTo("meridian-knowledge");
            McpSchema.Tool tool = anonymous.listTools().tools().getFirst();
            assertThat(tool.name()).isEqualTo("knowledge_search");
            assertThat(tool.inputSchema().required()).containsExactly("query");
            assertThat(tool.annotations().readOnlyHint()).isTrue();

            JsonNode hits = call(anonymous, Map.of("query", "年终奖发几个月", "top_k", 50));
            assertThat(filenames(hits)).hasSize(5).containsOnly("attendance.md");

            JsonNode explicit =
                    call(anonymous, Map.of("query", "年休假", "knowledge_base_ids", List.of(base), "top_k", 2));
            assertThat(explicit.get("data")).hasSize(2);
            assertThat(explicit.get("data")
                            .get(0)
                            .get("source")
                            .get("heading_path")
                            .isArray())
                    .isTrue();
        }
        try (McpSyncClient confidential = client(tenant.key(), "level:confidential")) {
            confidential.initialize();
            JsonNode hits = call(confidential, Map.of("query", "年终奖发几个月", "top_k", 50));
            assertThat(filenames(hits)).hasSize(10).contains("compensation.md");
        }
        List<String> sources = db().queryForList(
                        "SELECT payload->>'source' FROM kb.outbox WHERE topic = 'usage' AND payload->>'kind' = 'knowledge_search_calls'"
                                + " AND payload->>'tenant_id' = ?",
                        String.class,
                        tenant.tenantId());
        assertThat(sources).hasSize(3).containsOnly("mcp");
    }

    @Test
    void errorsAreToolResultsAndAuthenticationIsEnforced() throws Exception {
        Tenant tenant = newTenant();
        String base = createKnowledgeBase(tenant);
        Tenant other = newTenant();
        String foreign = createKnowledgeBase(other);
        ingest(tenant, base, "attendance.md", KnowledgeSearchIT.evalCorpus("attendance.md"), null);

        try (McpSyncClient client = client(tenant.key(), null)) {
            client.initialize();
            McpSchema.CallToolResult notMine = client.callTool(new McpSchema.CallToolRequest(
                    "knowledge_search", Map.of("query", "年假", "knowledge_base_ids", List.of(foreign))));
            assertThat(notMine.isError()).isTrue();
            assertThat(text(notMine)).contains("knowledge_base.not_found");
            McpSchema.CallToolResult blank =
                    client.callTool(new McpSchema.CallToolRequest("knowledge_search", Map.of("query", "  ")));
            assertThat(blank.isError()).isTrue();
        }

        // 没有凭据、无效的 Key、缺少 knowledge:read：在初始化之前就被过滤器拒绝
        assertThat(rawInitialize(null).statusCode()).isEqualTo(401);
        assertThat(rawInitialize("mk_live_invalid0000000000000000000000").statusCode())
                .isEqualTo(401);
        String writeOnly = PLATFORM.issue(tenant.tenantId(), tenant.projectId(), "knowledge:write");
        HttpResponse<String> forbidden = rawInitialize(writeOnly);
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(JsonMappers.lenient().readTree(forbidden.body()).get("code").asString())
                .isEqualTo("auth.insufficient_scope");
        assertThatThrownBy(() -> {
                    try (McpSyncClient rejected = client(writeOnly, null)) {
                        rejected.initialize();
                    }
                })
                .isInstanceOf(RuntimeException.class);
    }

    private McpSyncClient client(String key, String aclTags) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(
                        "http://127.0.0.1:" + port)
                .endpoint("/mcp/knowledge")
                .customizeRequest(request -> {
                    request.header("Authorization", "Bearer " + key);
                    if (aclTags != null) {
                        request.header("Meridian-Acl-Tags", aclTags);
                    }
                })
                .build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
    }

    private HttpResponse<String> rawInitialize(String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp/knowledge"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                         "capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""));
        if (key != null) {
            request.header("Authorization", "Bearer " + key);
        }
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static JsonNode call(McpSyncClient client, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("knowledge_search", arguments));
        assertThat(result.isError()).as(text(result)).isFalse();
        return JsonMappers.lenient().readTree(text(result));
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    private static List<String> filenames(JsonNode result) {
        List<String> names = new ArrayList<>();
        result.get("data").forEach(hit -> names.add(hit.get("filename").asString()));
        return names;
    }
}
