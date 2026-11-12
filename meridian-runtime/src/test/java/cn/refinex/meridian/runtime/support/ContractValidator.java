package cn.refinex.meridian.runtime.support;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.Response;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.MessageResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.schema.SchemaValidator;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 契约测试的校验器：按 {@code meridian-api} 发布的 {@code openapi/meridian-v1.yaml} 校验请求、响应与 SSE 帧（ADR-0029），
 * 内部接口按 {@code openapi/meridian-internal-v1.yaml} 校验（T09b）。
 * 校验库默认严格：未声明的字段、缺少的必填字段、类型与枚举不符都是错误。
 *
 * <p>记录被校验过的操作（{@code METHOD 路径模板}），{@code SessionApiContractIT} 据此断言每个端点都经过了校验。
 */
public final class ContractValidator {

    private static final String SPEC = "openapi/meridian-v1.yaml";

    private static final OpenApiInteractionValidator INTERACTIONS =
            OpenApiInteractionValidator.createForSpecificationUrl(SPEC).build();

    /** 事件流的校验器：连接上可能一帧都没有（客户端已收到全部事件、会话已终止），空的响应体是合法的。 */
    private static final OpenApiInteractionValidator STREAMS = OpenApiInteractionValidator.createForSpecificationUrl(
                    SPEC)
            .withLevelResolver(LevelResolver.create()
                    .withLevel("validation.response.body.missing", ValidationReport.Level.IGNORE)
                    .build())
            .build();

    private static final OpenAPI API = parse();

    private static final String INTERNAL_SPEC = "openapi/meridian-internal-v1.yaml";

    private static final OpenApiInteractionValidator INTERNAL =
            OpenApiInteractionValidator.createForSpecificationUrl(INTERNAL_SPEC).build();

    private static final SchemaValidator SCHEMAS = new SchemaValidator(API, new MessageResolver());

    private static final Set<String> VALIDATED = ConcurrentHashMap.newKeySet();

    /** runtime 提供的公开标签，与本模块 POM 中 {@code generate-public-server} 的 {@code apisToGenerate} 相同。 */
    private static final Set<String> RUNTIME_TAGS =
            Set.of("Sessions", "Events", "Threads", "PendingActions", "Models", "Webhooks", "Files", "Workspace");

    /**
     * 由别的服务提供的公开标签：{@code Agents}（ADR-0047，契约校验在其 {@code AgentVersionIT}）与 {@code Usage}（ADR-0048，契约校验在其
     * {@code UsageReportIT}）在 control-plane。
     */
    private static final Set<String> OTHER_SERVICE_TAGS = Set.of("Agents", "Usage");

    private ContractValidator() {}

    /**
     * 校验一次交互的请求与响应。
     *
     * @param operation 操作，形如 {@code POST /v1/sessions/{session_id}:archive}，用于统计覆盖
     * @param request 请求
     * @param response 响应
     * @throws AssertionError 不符合规范时
     */
    public static void validate(String operation, Request request, Response response) {
        report(operation, INTERACTIONS.validate(request, response));
    }

    /**
     * 校验一次事件流交互的请求、状态码与响应头；帧的内容用 {@link #validateEvent} 逐帧校验。
     *
     * @param operation 操作
     * @param request 请求
     * @param response 响应
     * @throws AssertionError 不符合规范时
     */
    public static void validateStream(String operation, Request request, Response response) {
        report(operation, STREAMS.validate(request, response));
    }

    /**
     * 只校验响应：请求本身故意不合法（缺少认证、字段错误）时使用。
     *
     * @param operation 操作
     * @param path 请求路径
     * @param method HTTP 方法
     * @param response 响应
     * @throws AssertionError 不符合规范时
     */
    public static void validateResponse(String operation, String path, Request.Method method, Response response) {
        report(operation, INTERACTIONS.validateResponse(path, method, response));
    }

    /**
     * 校验一个 SSE 帧的 {@code data}：持久化事件按 {@code SessionEvent}，只在流上的事件按 {@code StreamOnlyEvent}。
     *
     * @param envelope 事件信封 JSON
     * @param persisted 是否为持久化事件（帧有 {@code id:}）
     * @throws AssertionError 不符合规范时
     */
    public static void validateEvent(String envelope, boolean persisted) {
        Schema<?> schema = API.getComponents().getSchemas().get(persisted ? "SessionEvent" : "StreamOnlyEvent");
        ValidationReport report = SCHEMAS.validate(envelope, schema, "sse.data");
        if (report.hasErrors()) {
            throw new AssertionError("SSE 帧不符合规范：" + report.getMessages() + "\n" + envelope);
        }
    }

    /**
     * 按名称校验一段 JSON 是否符合公开规范中的某个 schema，例如流式对话补全的每个 {@code ChatCompletionChunk}。
     *
     * @param json JSON 文本
     * @param schemaName {@code components/schemas} 中的名称
     * @throws AssertionError 不符合规范时
     */
    public static void validateSchema(String json, String schemaName) {
        Schema<?> schema = API.getComponents().getSchemas().get(schemaName);
        ValidationReport report = SCHEMAS.validate(json, schema, schemaName);
        if (report.hasErrors()) {
            throw new AssertionError(schemaName + " 不符合规范：" + report.getMessages() + "\n" + json);
        }
    }

    /**
     * 按内部接口规范校验一次交互。
     *
     * @param request 请求
     * @param response 响应
     * @throws AssertionError 不符合规范时
     */
    public static void validateInternal(Request request, Response response) {
        ValidationReport report = INTERNAL.validate(request, response);
        if (report.hasErrors()) {
            throw new AssertionError("内部接口不符合规范：" + report.getMessages());
        }
    }

    /**
     * 按内部接口规范只校验响应：请求本身故意不合法（缺少字段）时使用。
     *
     * @param path 请求路径
     * @param method HTTP 方法
     * @param response 响应
     * @throws AssertionError 不符合规范时
     */
    public static void validateInternalResponse(String path, Request.Method method, Response response) {
        ValidationReport report = INTERNAL.validateResponse(path, method, response);
        if (report.hasErrors()) {
            throw new AssertionError("内部接口的响应不符合规范：" + report.getMessages());
        }
    }

    /**
     * 被校验过的操作。
     *
     * @return 操作集合
     */
    public static Set<String> validatedOperations() {
        return Set.copyOf(VALIDATED);
    }

    /**
     * 规范中由 runtime 提供的全部操作：标签属于 {@link #RUNTIME_TAGS}。其余操作的标签必须属于 {@link #OTHER_SERVICE_TAGS}
     * （由别的服务提供、在那里做契约校验），否则抛异常，新增标签时要在这里决定归属。
     *
     * @return 操作集合，形如 {@code GET /v1/sessions}
     * @throws IllegalStateException 规范中有归属不明的标签时
     */
    public static Set<String> allOperations() {
        Set<String> operations = new TreeSet<>();
        API.getPaths()
                .forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                    String name = method.name() + " " + path;
                    List<String> tags = operation.getTags() == null ? List.of() : operation.getTags();
                    if (tags.stream().anyMatch(RUNTIME_TAGS::contains)) {
                        operations.add(name);
                    } else if (tags.isEmpty() || !OTHER_SERVICE_TAGS.containsAll(tags)) {
                        throw new IllegalStateException(
                                name + " 的标签 " + tags + " 归属不明：加入 RUNTIME_TAGS 或 OTHER_SERVICE_TAGS");
                    }
                }));
        return operations;
    }

    private static void report(String operation, ValidationReport report) {
        if (report.hasErrors()) {
            throw new AssertionError(operation + " 不符合规范：" + report.getMessages());
        }
        VALIDATED.add(operation);
    }

    private static OpenAPI parse() {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        return new OpenAPIV3Parser().read(SPEC, null, options);
    }
}
