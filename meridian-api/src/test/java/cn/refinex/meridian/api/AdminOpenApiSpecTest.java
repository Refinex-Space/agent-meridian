package cn.refinex.meridian.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 管理 API 规范 {@code meridian-admin-v1.yaml} 的校验（T14a，ADR-0042）：按 OpenAPI 3.1 解析无错误，端点与交付范围一致、都在
 * {@code /admin/v1/} 下并要求控制台身份，写操作都接受 {@code Idempotency-Key}，写作约束与公开 API 相同。
 */
class AdminOpenApiSpecTest {

    /** 规范在类路径中的位置。 */
    static final String SPEC = "openapi/meridian-admin-v1.yaml";

    /** T14a 交付的管理接口：租户与项目、成员与角色、API Key 的增删改查。 */
    private static final Set<String> EXPECTED_OPERATIONS = Set.of(
            "POST /admin/v1/tenants",
            "GET /admin/v1/tenants",
            "GET /admin/v1/tenants/{tenant_id}",
            "PATCH /admin/v1/tenants/{tenant_id}",
            "DELETE /admin/v1/tenants/{tenant_id}",
            "PUT /admin/v1/tenants/{tenant_id}/quotas",
            "GET /admin/v1/tenants/{tenant_id}/settings",
            "PUT /admin/v1/tenants/{tenant_id}/settings",
            "POST /admin/v1/tenants/{tenant_id}/projects",
            "GET /admin/v1/tenants/{tenant_id}/projects",
            "GET /admin/v1/tenants/{tenant_id}/projects/{project_id}",
            "PATCH /admin/v1/tenants/{tenant_id}/projects/{project_id}",
            "DELETE /admin/v1/tenants/{tenant_id}/projects/{project_id}",
            "POST /admin/v1/tenants/{tenant_id}/members",
            "GET /admin/v1/tenants/{tenant_id}/members",
            "GET /admin/v1/tenants/{tenant_id}/members/{member_id}",
            "PATCH /admin/v1/tenants/{tenant_id}/members/{member_id}",
            "DELETE /admin/v1/tenants/{tenant_id}/members/{member_id}",
            "POST /admin/v1/tenants/{tenant_id}/role-bindings",
            "GET /admin/v1/tenants/{tenant_id}/role-bindings",
            "DELETE /admin/v1/tenants/{tenant_id}/role-bindings/{role_binding_id}",
            "POST /admin/v1/tenants/{tenant_id}/projects/{project_id}/api-keys",
            "GET /admin/v1/tenants/{tenant_id}/projects/{project_id}/api-keys",
            "GET /admin/v1/tenants/{tenant_id}/projects/{project_id}/api-keys/{api_key_id}",
            "PATCH /admin/v1/tenants/{tenant_id}/projects/{project_id}/api-keys/{api_key_id}",
            "POST /admin/v1/tenants/{tenant_id}/projects/{project_id}/api-keys/{api_key_id}:revoke");

    private static final Set<PathItem.HttpMethod> WRITES = Set.of(
            PathItem.HttpMethod.POST, PathItem.HttpMethod.PUT, PathItem.HttpMethod.PATCH, PathItem.HttpMethod.DELETE);

    private static final Pattern SNAKE_CASE = Pattern.compile("^[a-z][a-z0-9_]*$");

    private static OpenAPI api;
    private static List<String> parseMessages;

    @BeforeAll
    static void parse() {
        ParseOptions options = new ParseOptions();
        options.setResolve(false);
        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(SPEC, null, options);
        parseMessages = result.getMessages() == null ? List.of() : result.getMessages();
        api = result.getOpenAPI();
    }

    @Test
    void specParsesAsOpenApi31WithoutMessages() {
        assertThat(parseMessages).isEmpty();
        assertThat(api.getSpecVersion()).isEqualTo(SpecVersion.V31);
    }

    @Test
    void endpointsAreUnderAdminPrefixAndRequireConsoleIdentity() {
        assertThat(operations().keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED_OPERATIONS);
        assertThat(api.getSecurity())
                .singleElement()
                .satisfies(requirement -> assertThat(requirement).containsOnlyKeys("ConsoleAuth"));
        operations().forEach((key, operation) -> {
            assertThat(operation.getOperationId()).as(key).isNotBlank();
            assertThat(operation.getSecurity()).as(key + " 沿用全局的控制台身份").isNull();
        });
    }

    @Test
    void everyWriteAcceptsIdempotencyKey() {
        api.getPaths()
                .forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                    if (!WRITES.contains(method)) {
                        return;
                    }
                    List<String> refs = operation.getParameters() == null
                            ? List.of()
                            : operation.getParameters().stream()
                                    .map(Parameter::get$ref)
                                    .toList();
                    assertThat(refs).as(method + " " + path).contains("#/components/parameters/IdempotencyKey");
                }));
    }

    @Test
    void everyResponseCarriesRequestIdAndErrorsAreProblemJson() {
        operations()
                .forEach((key, operation) -> operation.getResponses().forEach((status, response) -> {
                    ApiResponse resolved = response.get$ref() == null
                            ? response
                            : api.getComponents()
                                    .getResponses()
                                    .get(response.get$ref()
                                            .substring(response.get$ref().lastIndexOf('/') + 1));
                    String where = key + " " + status;
                    assertThat(resolved.getHeaders()).as(where).containsKey("X-Request-Id");
                    if (status.startsWith("4") || status.startsWith("5") || status.equals("default")) {
                        assertThat(resolved.getContent()).as(where).containsOnlyKeys("application/problem+json");
                    }
                }));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"}) // swagger-models 的 getProperties() 返回原始类型的 Map<String, Schema>
    void propertyNamesAreSnakeCaseAndDescribed() {
        List<String> problems = new ArrayList<>();
        api.getComponents().getSchemas().forEach((name, schema) -> {
            if (schema.getProperties() == null) {
                return;
            }
            Map<String, Schema> properties = schema.getProperties();
            properties.forEach((property, propertySchema) -> {
                if (!SNAKE_CASE.matcher(property).matches()) {
                    problems.add(name + "." + property + " 不是 snake_case");
                }
                boolean described = propertySchema.get$ref() != null
                        || (propertySchema.getDescription() != null
                                && !propertySchema.getDescription().isBlank());
                if (!described) {
                    problems.add(name + "." + property + " 缺少说明");
                }
            });
        });
        assertThat(problems).isEmpty();
    }

    private static Map<String, Operation> operations() {
        Map<String, Operation> result = new LinkedHashMap<>();
        api.getPaths()
                .forEach((path, item) -> item.readOperationsMap()
                        .forEach((method, operation) -> result.put(method.name() + " " + path, operation)));
        return result;
    }
}
