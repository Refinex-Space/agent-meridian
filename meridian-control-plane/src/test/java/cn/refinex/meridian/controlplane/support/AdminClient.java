package cn.refinex.meridian.controlplane.support;

import cn.refinex.meridian.testsupport.contract.ContractCoverage;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 调用 control-plane 的测试客户端：管理 API 与内部接口的每次交互都按对应的规范校验请求与响应（ADR-0029、ADR-0042），并记进
 * {@link ContractCoverage}，由 {@code ContractCoverageIT} 核对覆盖（R2）。
 */
public final class AdminClient {

    private static final OpenApiInteractionValidator ADMIN = OpenApiInteractionValidator.createForSpecificationUrl(
                    "openapi/meridian-admin-v1.yaml")
            .build();

    private static final OpenApiInteractionValidator INTERNAL = OpenApiInteractionValidator.createForSpecificationUrl(
                    "openapi/meridian-internal-v1.yaml")
            .build();

    private static final OpenApiInteractionValidator PUBLIC = OpenApiInteractionValidator.createForSpecificationUrl(
                    "openapi/meridian-v1.yaml")
            .build();

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final HttpClient HTTP =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private final String baseUrl;

    AdminClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * 以控制台用户调用管理 API。
     *
     * @param method HTTP 方法
     * @param path 路径（可含查询串）
     * @param token 控制台令牌；可为空，表示不带 {@code Authorization}
     * @param body 请求体 JSON；可为空
     * @return 响应
     */
    public Reply admin(String method, String path, String token, String body) {
        return admin(method, path, token, body, Map.of());
    }

    /**
     * 以控制台用户调用管理 API，带额外的请求头。
     *
     * @param method HTTP 方法
     * @param path 路径（可含查询串）
     * @param token 控制台令牌；可为空
     * @param body 请求体 JSON；可为空
     * @param headers 额外的请求头
     * @return 响应
     */
    public Reply admin(String method, String path, String token, String body, Map<String, String> headers) {
        Map<String, String> all = new LinkedHashMap<>(headers);
        if (token != null) {
            all.put("Authorization", "Bearer " + token);
        }
        return send(ADMIN, method, path, body, all);
    }

    /**
     * 以服务凭据调用内部接口。
     *
     * @param method HTTP 方法
     * @param path 路径
     * @param credential 服务凭据；可为空
     * @param body 请求体 JSON；可为空
     * @return 响应
     */
    public Reply internal(String method, String path, String credential, String body) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (credential != null) {
            headers.put("Authorization", "Bearer " + credential);
        }
        return send(INTERNAL, method, path, body, headers);
    }

    /**
     * 调本应用的公开接口（{@code /v1/agents}），按 {@code meridian-v1.yaml} 校验请求与响应。
     *
     * @param method 方法
     * @param path 路径（含查询参数）
     * @param credential API Key 或控制台用户的令牌；可为空
     * @param project {@code Meridian-Project}；可为空
     * @return 响应
     */
    public Reply api(String method, String path, String credential, String project) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (credential != null) {
            headers.put("Authorization", "Bearer " + credential);
        }
        if (project != null) {
            headers.put("Meridian-Project", project);
        }
        return send(PUBLIC, method, path, null, headers);
    }

    /**
     * 向令牌端点换取服务 JWT（ADR-0044），按内部接口规范校验。
     *
     * @param service 服务名；为空时不带 Basic 认证
     * @param secret 客户端密钥
     * @param audience 目标服务
     * @return 响应
     */
    public Reply serviceToken(String service, String secret, String audience) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (service != null) {
            headers.put(
                    "Authorization",
                    "Basic "
                            + java.util.Base64.getEncoder()
                                    .encodeToString((service + ":" + secret)
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        return send(INTERNAL, "POST", "/oauth2/token", "grant_type=client_credentials&audience=" + audience, headers);
    }

    private Reply send(
            OpenApiInteractionValidator validator,
            String method,
            String path,
            String body,
            Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null && !headers.containsKey("Content-Type")) {
            builder.header("Content-Type", "application/json");
        }
        headers.forEach(builder::header);
        HttpResponse<String> response;
        try {
            response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        URI uri = URI.create(baseUrl + path);
        SimpleRequest.Builder request = new SimpleRequest.Builder(Request.Method.valueOf(method), uri.getRawPath());
        headers.forEach(request::withHeader);
        if (body != null) {
            request.withContentType(headers.getOrDefault("Content-Type", "application/json"))
                    .withBody(body);
        }
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                request.withQueryParam(parts[0], parts.length > 1 ? parts[1] : "");
            }
        }
        SimpleResponse.Builder validated = SimpleResponse.Builder.status(response.statusCode());
        response.headers().map().forEach((name, values) -> validated.withHeader(name, values));
        if (!response.body().isEmpty()) {
            validated.withBody(response.body());
        }
        ValidationReport report = validator.validate(request.build(), validated.build());
        if (report.hasErrors()) {
            throw new AssertionError(method + " " + path + " 不符合规范：" + report.getMessages() + "\n" + response.body());
        }
        ContractCoverage.record(specOf(validator), method, uri.getRawPath());
        // CSV 导出（T19b）等非 JSON 的响应只给原文
        boolean isJson = response.headers()
                .firstValue("Content-Type")
                .map(type -> type.contains("json"))
                .orElse(true);
        JsonNode json = response.body().isEmpty() || !isJson ? null : JSON.readTree(response.body());
        return new Reply(response.statusCode(), response.headers().map(), json, response.body());
    }

    private static String specOf(OpenApiInteractionValidator validator) {
        if (validator == ADMIN) {
            return ContractCoverage.ADMIN;
        }
        return validator == INTERNAL ? ContractCoverage.INTERNAL : ContractCoverage.PUBLIC;
    }

    /**
     * 一次响应。
     *
     * @param status 状态码
     * @param headers 响应头
     * @param json 响应体；没有时为空
     * @param raw 响应体原文
     */
    public record Reply(int status, Map<String, java.util.List<String>> headers, JsonNode json, String raw) {

        /**
         * 取一个字段的文本值。
         *
         * @param field 字段名
         * @return 文本
         */
        public String text(String field) {
            return json.get(field).asString();
        }

        /**
         * 取第一个同名响应头。
         *
         * @param name 响应头名（不区分大小写）
         * @return 值；没有时为空
         */
        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst())
                    .findFirst()
                    .orElse(null);
        }
    }
}
