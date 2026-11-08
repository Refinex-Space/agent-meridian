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
 * 内部接口规范 {@code meridian-internal-v1.yaml} 的校验（T09b）：按 OpenAPI 3.1 解析无错误，端点与架构设计「知识库架构」引用的
 * 内部接口一致，全部要求服务凭据，写作约束与公开 API 相同。
 */
class InternalOpenApiSpecTest {

    /** 规范在类路径中的位置。 */
    static final String SPEC = "openapi/meridian-internal-v1.yaml";

    /** 架构设计「写入流程」「检索流程」中 knowledge 调用 runtime 的内部接口。 */
    private static final Set<String> EXPECTED_OPERATIONS =
            Set.of("POST /internal/v1/embeddings", "POST /internal/v1/rerank");

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
            assertThat(operation.getSecurity()).as(key + " 沿用全局的服务凭据").isNull();
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
