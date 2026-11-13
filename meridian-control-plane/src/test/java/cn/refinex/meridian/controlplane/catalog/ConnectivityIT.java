package cn.refinex.meridian.controlplane.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.controlplane.support.AdminClient.Reply;
import cn.refinex.meridian.controlplane.support.ControlPlaneTestBase;
import cn.refinex.meridian.controlplane.support.TenantFixture;
import cn.refinex.meridian.testsupport.TestDatabase;
import cn.refinex.meridian.testsupport.mcp.TestMcpServer;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 连接状态（T19b，ADR-0053）：供应商账号的连通性测试按对方的响应给出 {@code ok}、{@code auth_failed}、{@code unreachable} 并记进
 * {@code last_check}；MCP 服务登记与重新探测时记下连接状态，重新探测失败时清单不变、状态变为 {@code error}。
 */
class ConnectivityIT extends ControlPlaneTestBase {

    @Test
    void providerConnectivityIsTestedWithTheStoredCredential() throws IOException {
        TenantFixture fixture = TenantFixture.create(client());
        String base = "/admin/v1/tenants/" + fixture.get("tenant");
        String owner = token("owner");
        List<String> authorizations = new CopyOnWriteArrayList<>();
        HttpServer provider = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        provider.createContext("/v1/models", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(String.valueOf(authorization));
            byte[] body = "{\"data\":[]}".getBytes();
            exchange.sendResponseHeaders("Bearer good-key".equals(authorization) ? 200 : 401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        provider.start();
        String baseUrl = "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1";
        try {
            String secret = client().admin(
                            "POST", base + "/secrets", owner, "{\"name\":\"连通凭据\",\"value\":\"good-key\"}")
                    .text("id");
            String account = client().admin(
                            "POST",
                            base + "/provider-accounts",
                            owner,
                            "{\"name\":\"连通账号\",\"provider\":\"openai_compatible\",\"base_url\":\"" + baseUrl
                                    + "\",\"secret_id\":\"" + secret + "\"}")
                    .text("id");
            assertThat(client().admin("GET", base + "/provider-accounts/" + account, owner, null)
                            .json()
                            .get("last_check")
                            .isNull())
                    .isTrue();

            Reply ok = client().admin("POST", base + "/provider-accounts/" + account + ":test", token("admin"), null);
            assertThat(ok.status()).isEqualTo(200);
            assertThat(ok.text("status")).isEqualTo("ok");
            assertThat(ok.json().get("http_status").asInt()).isEqualTo(200);
            assertThat(ok.json().get("message").isNull()).isTrue();
            assertThat(authorizations).containsExactly("Bearer good-key");
            Reply stored = client().admin("GET", base + "/provider-accounts/" + account, token("viewer"), null);
            assertThat(stored.json().get("last_check").get("status").asString()).isEqualTo("ok");

            // 凭据轮换成错的值：对方 401
            client().admin("POST", base + "/secrets/" + secret + ":rotate", owner, "{\"value\":\"bad-key\"}");
            Reply denied = client().admin("POST", base + "/provider-accounts/" + account + ":test", owner, null);
            assertThat(denied.text("status")).isEqualTo("auth_failed");
            assertThat(denied.json().get("http_status").asInt()).isEqualTo(401);
            assertThat(denied.text("message")).doesNotContain("bad-key");
        } finally {
            provider.stop(0);
        }
        // 服务停了：连不上，不是错误响应
        String account = client().admin("GET", base + "/provider-accounts?limit=100", owner, null)
                .json()
                .get("data")
                .valueStream()
                .filter(node -> node.get("name").asString().equals("连通账号"))
                .findFirst()
                .orElseThrow()
                .get("id")
                .asString();
        Reply unreachable = client().admin("POST", base + "/provider-accounts/" + account + ":test", owner, null);
        assertThat(unreachable.status()).isEqualTo(200);
        assertThat(unreachable.text("status")).isEqualTo("unreachable");
        assertThat(unreachable.json().get("http_status").isNull()).isTrue();
        assertThat(audits(fixture.get("tenant"), account)).contains("provider_account.tested");
    }

    @Test
    void mcpServersRecordTheirConnectionState() {
        TenantFixture fixture = TenantFixture.create(client());
        String base = "/admin/v1/tenants/" + fixture.get("tenant") + "/projects/" + fixture.get("project");
        String owner = token("owner");
        String secret = client().admin(
                        "POST",
                        "/admin/v1/tenants/" + fixture.get("tenant") + "/secrets",
                        owner,
                        "{\"name\":\"连接令牌\",\"value\":\"Bearer conn-token\"}")
                .text("id");
        TestMcpServer server = TestMcpServer.start("conn-token");
        String id;
        try {
            Reply created = client().admin(
                            "POST",
                            base + "/mcp-servers",
                            owner,
                            TenantFixture.mcpServer("conn_probe", "streamable_http", server.streamableUrl(), secret));
            assertThat(created.status()).as(created.raw()).isEqualTo(201);
            id = created.text("id");
            assertThat(created.json().get("last_check").get("status").asString())
                    .isEqualTo("ok");
            assertThat(client().admin("POST", base + "/mcp-servers/" + id + ":probe", owner, null)
                            .json()
                            .get("last_check")
                            .get("status")
                            .asString())
                    .isEqualTo("ok");
        } finally {
            server.close();
        }
        Reply failed = client().admin("POST", base + "/mcp-servers/" + id + ":probe", owner, null);
        assertThat(failed.status()).isEqualTo(400);
        assertThat(failed.text("code")).isEqualTo("mcp_server.probe_failed");
        Reply after = client().admin("GET", base + "/mcp-servers/" + id, token("viewer"), null);
        assertThat(after.json().get("last_check").get("status").asString()).isEqualTo("error");
        assertThat(after.json().get("last_check").get("message").asString()).isNotBlank();
        assertThat(after.json().get("tools")).as("失败时清单不变").isNotEmpty();
    }

    private static List<String> audits(String tenantId, String resourceId) {
        return JdbcClient.create(TestDatabase.migratorDataSource())
                .sql("""
                        SELECT payload->>'action' FROM cp.outbox
                         WHERE topic = 'audit' AND payload->>'tenant_id' = ? AND payload->>'resource_id' = ?
                        """)
                .param(tenantId)
                .param(resourceId)
                .query(String.class)
                .list();
    }
}
