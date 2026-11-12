package cn.refinex.meridian.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.MediaType;
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
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 公开 API 规范 {@code meridian-v1.yaml} 的校验（T07-A01）：按 OpenAPI 3.1 解析无错误，端点与事件类型与架构设计「API 设计」
 * 一致，并满足编码规范「API」与 {@code meridian-api} 的 CLAUDE.md 中的写作约束。
 *
 * <p>架构设计中的清单在这里逐项列出：改规范时先改架构设计，再改这里的清单与规范。
 */
class OpenApiSpecTest {

    /** 规范在类路径中的位置。 */
    static final String SPEC = "openapi/meridian-v1.yaml";

    /** 架构设计「资源」表中会话、事件、线程、待处理动作四行的全部端点（T07 范围）。 */
    private static final Set<String> EXPECTED_OPERATIONS = Set.of(
            "POST /v1/sessions",
            "GET /v1/sessions",
            "GET /v1/sessions/{session_id}",
            "PATCH /v1/sessions/{session_id}",
            "DELETE /v1/sessions/{session_id}",
            "POST /v1/sessions/{session_id}:archive",
            "POST /v1/sessions/{session_id}/events",
            "GET /v1/sessions/{session_id}/events",
            "GET /v1/sessions/{session_id}/stream",
            "GET /v1/sessions/{session_id}/threads",
            "GET /v1/sessions/{session_id}/threads/{thread_id}/stream",
            "GET /v1/sessions/{session_id}/pending-actions",
            "GET /v1/sessions/{session_id}/workspace/files/{path}",
            "PUT /v1/sessions/{session_id}/workspace/files/{path}",
            "GET /v1/sessions/{session_id}/workspace:list",
            "POST /v1/files",
            "GET /v1/files/{file_id}",
            "GET /v1/files/{file_id}/content",
            "POST /v1/chat/completions",
            "POST /v1/responses",
            "POST /v1/embeddings",
            "GET /v1/agents",
            "GET /v1/agents/{agent_id}",
            "GET /v1/usage",
            "GET /v1/webhook-deliveries",
            "GET /v1/webhook-deliveries/{delivery_id}",
            "POST /v1/webhook-deliveries/{delivery_id}:redeliver",
            "POST /v1/knowledge-bases",
            "GET /v1/knowledge-bases",
            "GET /v1/knowledge-bases/{knowledge_base_id}",
            "POST /v1/knowledge-bases/{knowledge_base_id}/documents",
            "GET /v1/knowledge-bases/{knowledge_base_id}/documents",
            "GET /v1/knowledge-bases/{knowledge_base_id}/documents/{document_id}",
            "DELETE /v1/knowledge-bases/{knowledge_base_id}/documents/{document_id}",
            "POST /v1/knowledge-bases/{knowledge_base_id}/documents/{document_id}:reprocess");

    /** 架构设计「事件」的事件类型清单中，持久化的 agent、session、span 与 user 四个域。 */
    private static final Set<String> EXPECTED_SESSION_EVENTS = Set.of(
            "user.message",
            "user.interrupt",
            "user.tool_confirmation",
            "user.custom_tool_result",
            "user.recovery_confirmation",
            "agent.message",
            "agent.thinking",
            "agent.tool_use",
            "agent.tool_result",
            "agent.mcp_tool_use",
            "agent.mcp_tool_result",
            "agent.custom_tool_use",
            "agent.context_compacted",
            "session.status_running",
            "session.status_idle",
            "session.status_requires_action",
            "session.status_rescheduled",
            "session.status_terminated",
            "session.error",
            "session.usage",
            "session.message_consumed",
            "session.thread_created",
            "session.thread_status_running",
            "session.thread_status_idle",
            "span.model_request_start",
            "span.model_request_end");

    /** 架构设计「事件」中只在流上出现的事件。 */
    private static final Set<String> EXPECTED_STREAM_ONLY_EVENTS = Set.of("event_start", "event_delta", "ping");

    /** 调用方可以发送的事件：user 域。 */
    private static final Set<String> EXPECTED_USER_INPUTS = Set.of(
            "user.message",
            "user.interrupt",
            "user.tool_confirmation",
            "user.custom_tool_result",
            "user.recovery_confirmation");

    /** 事件信封的字段（架构设计「事件」）。 */
    private static final List<String> ENVELOPE_FIELDS =
            List.of("id", "seq", "session_id", "thread_id", "turn_id", "type", "created_at", "data");

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
        assertThat(api).isNotNull();
        assertThat(api.getSpecVersion()).isEqualTo(SpecVersion.V31);
        assertThat(api.getOpenapi()).isEqualTo("3.1.0");
    }

    @Test
    void endpointsMatchTheArchitectureResourceTable() {
        assertThat(operations().keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED_OPERATIONS);
    }

    @Test
    void everyOperationDeclaresIdTagAndScope() {
        operations().forEach((key, operation) -> {
            assertThat(operation.getOperationId()).as(key).isNotBlank();
            assertThat(operation.getTags()).as(key).hasSize(1);
            String expectedScope = key.startsWith("POST /v1/chat/")
                            || key.startsWith("POST /v1/embeddings")
                            || key.startsWith("POST /v1/responses")
                    ? "models:invoke"
                    : key.equals("POST /v1/files")
                            ? "files:write"
                            : key.contains(" /v1/knowledge-bases")
                                    ? key.startsWith("GET ") ? "knowledge:read" : "knowledge:write"
                                    : key.startsWith("GET ") ? "sessions:read" : "sessions:write";
            assertThat(operation.getSecurity()).as(key).hasSize(1);
            assertThat(operation.getSecurity().getFirst()).as(key).containsEntry("ApiKeyAuth", List.of(expectedScope));
        });
    }

    @Test
    void everyPostAcceptsAnIdempotencyKey() {
        operations().forEach((key, operation) -> {
            if (key.startsWith("POST ")) {
                assertThat(operation.getParameters())
                        .as(key)
                        .extracting(Parameter::get$ref)
                        .contains("#/components/parameters/IdempotencyKey");
            }
        });
    }

    @Test
    void everyResponseCarriesRequestIdAndErrorsAreProblemJson() {
        operations()
                .forEach((key, operation) -> operation.getResponses().forEach((status, response) -> {
                    ApiResponse resolved = resolve(response);
                    String where = key + " " + status;
                    assertThat(resolved.getHeaders()).as(where).containsKey("X-Request-Id");
                    if (status.startsWith("4") || status.startsWith("5") || status.equals("default")) {
                        assertThat(resolved.getContent()).as(where).containsOnlyKeys("application/problem+json");
                        MediaType media = resolved.getContent().get("application/problem+json");
                        assertThat(media.getSchema().get$ref()).as(where).isEqualTo("#/components/schemas/Problem");
                    }
                }));
    }

    @Test
    void persistedEventTypesMatchTheArchitectureEventTable() {
        assertThat(mappingOf("SessionEvent").keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED_SESSION_EVENTS);
        assertThat(mappingOf("StreamOnlyEvent").keySet())
                .containsExactlyInAnyOrderElementsOf(EXPECTED_STREAM_ONLY_EVENTS);
        assertThat(mappingOf("UserEventInput").keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED_USER_INPUTS);
    }

    @Test
    void everyEventEnvelopeIsFlatAndPinsItsType() {
        Map<String, String> envelopes = new LinkedHashMap<>(mappingOf("SessionEvent"));
        envelopes.putAll(mappingOf("StreamOnlyEvent"));
        envelopes.forEach((type, ref) -> {
            Schema<?> schema = schema(ref);
            assertThat(schema.getAllOf()).as(type).isNull();
            assertThat(schema.getProperties()).as(type).containsOnlyKeys(ENVELOPE_FIELDS.toArray(String[]::new));
            assertThat(schema.getRequired()).as(type).containsExactlyInAnyOrderElementsOf(ENVELOPE_FIELDS);
            assertThat(schema.getProperties().get("type").getConst()).as(type).isEqualTo(type);
        });
    }

    @Test
    void everyDiscriminatorBranchPinsTheDiscriminatorWithConst() {
        api.getComponents().getSchemas().forEach((name, schema) -> {
            if (schema.getDiscriminator() == null) {
                return;
            }
            String property = schema.getDiscriminator().getPropertyName();
            schema.getDiscriminator().getMapping().forEach((value, ref) -> {
                Schema<?> branch = schema(ref);
                assertThat(branch.getAllOf()).as(name + " → " + ref).isNull();
                assertThat(branch.getProperties().get(property).getConst())
                        .as(name + " → " + ref)
                        .isEqualTo(value);
            });
        });
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
                boolean selfDescribing = propertySchema.get$ref() != null || propertySchema.getConst() != null;
                if (!selfDescribing && isBlank(propertySchema.getDescription()) && !isPageProperty(property)) {
                    problems.add(name + "." + property + " 缺少说明");
                }
            });
        });
        assertThat(problems).isEmpty();
    }

    private static boolean isPageProperty(String property) {
        // 分页的 data 是列表本身，字段含义由所在的 *Page 说明
        return property.equals("data");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static Map<String, Operation> operations() {
        Map<String, Operation> result = new LinkedHashMap<>();
        api.getPaths()
                .forEach((path, item) -> item.readOperationsMap()
                        .forEach((method, operation) -> result.put(method.name() + " " + path, operation)));
        return result;
    }

    private static Map<String, String> mappingOf(String schemaName) {
        Schema<?> schema = api.getComponents().getSchemas().get(schemaName);
        assertThat(schema).as(schemaName).isNotNull();
        assertThat(schema.getDiscriminator()).as(schemaName).isNotNull();
        Map<String, String> mapping = schema.getDiscriminator().getMapping();
        Set<String> oneOfRefs = new TreeSet<>();
        schema.getOneOf().forEach(branch -> oneOfRefs.add(branch.get$ref()));
        assertThat(new TreeSet<>(mapping.values())).as(schemaName).isEqualTo(oneOfRefs);
        return mapping;
    }

    private static Schema<?> schema(String ref) {
        String name = ref.substring(ref.lastIndexOf('/') + 1);
        Schema<?> schema = api.getComponents().getSchemas().get(name);
        assertThat(schema).as(ref).isNotNull();
        return schema;
    }

    private static ApiResponse resolve(ApiResponse response) {
        if (response.get$ref() == null) {
            return response;
        }
        String name = response.get$ref().substring(response.get$ref().lastIndexOf('/') + 1);
        return api.getComponents().getResponses().get(name);
    }
}
