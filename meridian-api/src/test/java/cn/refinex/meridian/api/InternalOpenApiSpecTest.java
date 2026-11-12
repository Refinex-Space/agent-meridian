package cn.refinex.meridian.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.Schema;
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
 * 内部接口规范 {@code meridian-internal-v1.yaml} 的校验（T09b、T12b、T14a、T14b）：按 OpenAPI 3.1 解析无错误，端点与架构设计「知识库架构」
 * 「沙箱出网」引用的内部接口一致，全部要求服务凭据，写作约束与公开 API 相同。
 */
class InternalOpenApiSpecTest {

    /** 规范在类路径中的位置。 */
    static final String SPEC = "openapi/meridian-internal-v1.yaml";

    /**
     * 架构设计「写入流程」「检索流程」中 knowledge 调用 runtime 的内部接口，「沙箱出网」中出网代理的授权回调（T12b），以及
     * control-plane 的 API Key 校验与租户配额（T14a，ADR-0042）、凭据解析与模型目录（T15a，ADR-0045）。
     */
    private static final Set<String> EXPECTED_OPERATIONS = Set.of(
            "POST /internal/v1/embeddings",
            "POST /internal/v1/rerank",
            "POST /internal/v1/egress:authorize",
            "POST /internal/v1/api-keys:verify",
            "GET /internal/v1/tenants/{tenant_id}/quotas",
            "POST /internal/v1/project-access:resolve",
            "POST /internal/v1/secrets:resolve",
            "GET /internal/v1/models/{model_id}",
            "POST /internal/v1/agent-versions/{agent_version_id}:resolve",
            "POST /internal/v1/agents/{agent_id}:resolve",
            "GET /internal/v1/environments/{environment_id}",
            "GET /internal/v1/skills/{skill_id}/versions/{version}",
            "GET /internal/v1/projects/{project_id}/permission-rules",
            "POST /internal/v1/projects/{project_id}/permission-rules",
            "GET /internal/v1/tenants/{tenant_id}/settings",
            "POST /oauth2/token",
            "GET /oauth2/jwks");

    /** 不用服务 JWT 认证的端点（ADR-0044）：令牌端点用客户端凭据，JWKS 是公钥、不需要认证。 */
    private static final Map<String, List<String>> OWN_SECURITY =
            Map.of("POST /oauth2/token", List.of("ServiceClientAuth"), "GET /oauth2/jwks", List.of());

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
    void endpointsAreTheInternalModelEndpointsAndRequireServiceCredentials() {
        assertThat(operations().keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED_OPERATIONS);
        assertThat(api.getSecurity())
                .singleElement()
                .satisfies(requirement -> assertThat(requirement).containsOnlyKeys("ServiceAuth"));
        operations().forEach((key, operation) -> {
            assertThat(operation.getOperationId()).as(key).isNotBlank();
            if (OWN_SECURITY.containsKey(key)) {
                assertThat(operation.getSecurity())
                        .as(key)
                        .flatMap(requirement -> List.copyOf(requirement.keySet()))
                        .containsExactlyElementsOf(OWN_SECURITY.get(key));
            } else {
                assertThat(key.substring(key.indexOf(' ') + 1))
                        .as("服务 JWT 只保护 /internal/")
                        .startsWith("/internal/");
                assertThat(operation.getSecurity()).as(key + " 沿用全局的服务凭据").isNull();
            }
        });
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
