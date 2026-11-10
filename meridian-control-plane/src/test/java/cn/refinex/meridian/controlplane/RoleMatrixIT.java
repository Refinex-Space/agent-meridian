package cn.refinex.meridian.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.controlplane.support.AdminClient.Reply;
import cn.refinex.meridian.controlplane.support.ControlPlaneTestBase;
import cn.refinex.meridian.controlplane.support.TenantFixture;
import io.swagger.v3.parser.OpenAPIV3Parser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 角色矩阵的表驱动测试（T14a-A02、T14b-A04）：{@code rbac/role-matrix.psv} 中每个角色对每类管理操作的允许与拒绝，以 Keycloak 签发的
 * OIDC 令牌经真实的 HTTP、OIDC 校验、过滤器、拦截器与用例执行（用户标识取 {@code preferred_username}，平台管理员按组判定）。写操作每次用新建的租户，读操作共用一个；允许时要求 2xx，拒绝时要求 403 {@code access.forbidden}。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoleMatrixIT extends ControlPlaneTestBase {

    /** 表的列顺序。 */
    static final List<String> SUBJECTS =
            List.of(PLATFORM, "owner", "admin", "developer", "viewer", "approver", "outsider");

    private TenantFixture shared;

    @BeforeAll
    void sharedFixture() {
        shared = TenantFixture.create(client());
    }

    @ParameterizedTest(name = "{0} 由 {4} 执行：{5}")
    @MethodSource("cases")
    void roleMatrix(String id, String method, String path, String body, String subject, String expected) {
        TenantFixture fixture = method.equals("GET") ? shared : TenantFixture.create(client());
        Reply reply = client().admin(method, fixture.fill(path), token(subject), fixture.fill(body));

        if (expected.equals("A")) {
            assertThat(reply.status())
                    .as(id + " " + subject + "：" + reply.raw())
                    .isBetween(200, 299);
        } else {
            assertThat(reply.status())
                    .as(id + " " + subject + "：" + reply.raw())
                    .isEqualTo(403);
            assertThat(reply.text("code")).isEqualTo("access.forbidden");
        }
    }

    @Test
    void everyAdminOperationIsInTheTable() throws IOException {
        Set<String> inTable = new TreeSet<>();
        for (String[] row : rows()) {
            inTable.add(row[0].split("#")[0]);
        }
        Set<String> inSpec = new TreeSet<>();
        new OpenAPIV3Parser()
                .read("openapi/meridian-admin-v1.yaml")
                .getPaths()
                .values()
                .forEach(item -> item.readOperations().forEach(op -> inSpec.add(op.getOperationId())));

        assertThat(inTable).isEqualTo(inSpec);
    }

    @Test
    void requestsWithoutAConsoleTokenAreUnauthenticated() {
        Reply missing = client().admin("GET", "/admin/v1/tenants", null, null);
        // JWT 形状但签名、签发方都不对的令牌
        Reply wrong = client().admin(
                        "GET", "/admin/v1/tenants", "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJub2JvZHkifQ.c2lnbmF0dXJl", null);

        assertThat(missing.status()).isEqualTo(401);
        assertThat(missing.text("code")).isEqualTo("auth.unauthenticated");
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.text("code")).isEqualTo("auth.invalid_console_token");
    }

    Stream<Arguments> cases() throws IOException {
        List<Arguments> cases = new ArrayList<>();
        for (String[] row : rows()) {
            String[] expectations = row[4].trim().split("\\s+");
            assertThat(expectations).as(row[0]).hasSize(SUBJECTS.size());
            for (int i = 0; i < SUBJECTS.size(); i++) {
                String body = row[3].isBlank() ? null : row[3].trim();
                cases.add(Arguments.of(
                        row[0].trim(), row[1].trim(), row[2].trim(), body, SUBJECTS.get(i), expectations[i]));
            }
        }
        return cases.stream();
    }

    private static List<String[]> rows() throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (InputStream in = RoleMatrixIT.class.getResourceAsStream("/rbac/role-matrix.psv")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] cells = line.split("\\|", -1);
                for (int i = 0; i < cells.length; i++) {
                    cells[i] = cells[i].trim();
                }
                rows.add(cells);
            }
        }
        return rows;
    }
}
