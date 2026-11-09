package cn.refinex.meridian.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.controlplane.support.ControlPlaneTestBase;
import cn.refinex.meridian.testsupport.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** control-plane 启动（T14a-A01）：{@code cp} 迁移成功，健康检查与探针通过，应用运行在虚拟线程上。 */
class ControlPlaneApplicationIT extends ControlPlaneTestBase {

    @Test
    void migrationsCreateTheTenancyTables() {
        List<String> tables = JdbcClient.create(TestDatabase.migratorDataSource())
                .sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'cp' ORDER BY table_name")
                .query(String.class)
                .list();

        assertThat(tables)
                .contains(
                        "api_keys",
                        "flyway_schema_history",
                        "idempotency_keys",
                        "members",
                        "outbox",
                        "projects",
                        "role_bindings",
                        "tenant_settings",
                        "tenants");
    }

    @Test
    void healthAndProbesAreUp() throws Exception {
        for (String path :
                new String[] {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).as(path).contains("\"status\":\"UP\"");
        }
    }
}
