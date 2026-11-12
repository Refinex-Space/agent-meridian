package cn.refinex.meridian.knowledge.support;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 调用 knowledge 的测试客户端：每次交互都按 {@code meridian-v1.yaml}（公开 API）或 {@code meridian-internal-v1.yaml}（内部接口）校验
 * 请求与响应（ADR-0029）；multipart 上传只校验响应。校验过的操作记在 {@link #validatedOperations()}，契约测试据此核对覆盖。
 */
public final class ApiClient {

    private static final OpenApiInteractionValidator PUBLIC = OpenApiInteractionValidator.createForSpecificationUrl(
                    "openapi/meridian-v1.yaml")
            .build();

    private static final OpenApiInteractionValidator INTERNAL = OpenApiInteractionValidator.createForSpecificationUrl(
                    "openapi/meridian-internal-v1.yaml")
            .build();

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final HttpClient HTTP =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private static final Set<String> VALIDATED = ConcurrentHashMap.newKeySet();

    private final String baseUrl;

    /**
     * 创建客户端。
     *
     * @param baseUrl knowledge 的基础 URL
     */
    public ApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * 调用公开 API（JSON 或无请求体）。
     *
     * @param operation 规范中的操作，形如 {@code GET /v1/knowledge-bases/{knowledge_base_id}}
     * @param method 方法
     * @param path 实际路径（含查询参数）
     * @param credential API Key 或控制台令牌；可为空
     * @param body JSON 请求体；可为空
     * @param headers 其他请求头
     * @return 响应
     */
    public Reply api(
            String operation, String method, String path, String credential, String body, Map<String, String> headers) {
        Map<String, String> all = new LinkedHashMap<>(headers);
        if (credential != null) {
            all.put("Authorization", "Bearer " + credential);
        }
        if (body != null) {
            all.putIfAbsent("Content-Type", "application/json");
        }
        HttpResponse<String> response = send(
                method,
                path,
                body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body),
                all);
        SimpleRequest.Builder request = new SimpleRequest.Builder(Request.Method.valueOf(method), rawPath(path));
        all.forEach(request::withHeader);
        if (body != null) {
            request.withBody(body);
        }
        query(path).forEach(request::withQueryParam);
        check(operation, PUBLIC.validate(request.build(), toValidated(response)), response);
        return reply(response);
    }

    /**
     * 上传文档（multipart）。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param filename 文件名
     * @param content 内容
     * @param aclTags 逗号分隔的标签；可为空
     * @param credential API Key
     * @param idempotencyKey 幂等键；可为空
     * @return 响应
     */
    public Reply upload(
            String knowledgeBaseId,
            String filename,
            byte[] content,
            String aclTags,
            String credential,
            String idempotencyKey) {
        String boundary = "----meridian" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        part(
                body,
                boundary,
                "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename
                        + "\"\r\nContent-Type: application/octet-stream",
                content);
        if (aclTags != null) {
            part(
                    body,
                    boundary,
                    "Content-Disposition: form-data; name=\"acl_tags\"",
                    aclTags.getBytes(StandardCharsets.UTF_8));
        }
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + credential);
        headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        if (idempotencyKey != null) {
            headers.put("Idempotency-Key", idempotencyKey);
        }
        String path = "/v1/knowledge-bases/" + knowledgeBaseId + "/documents";
        HttpResponse<String> response =
                send("POST", path, HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()), headers);
        check(
                "POST /v1/knowledge-bases/{knowledge_base_id}/documents",
                PUBLIC.validateResponse(path, Request.Method.POST, toValidated(response)),
                response);
        return reply(response);
    }

    /**
     * 调用内部接口。
     *
     * @param method 方法
     * @param path 路径
     * @param credential 服务 JWT；可为空
     * @return 响应
     */
    public Reply internal(String method, String path, String credential) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (credential != null) {
            headers.put("Authorization", "Bearer " + credential);
        }
        HttpResponse<String> response = send(method, path, HttpRequest.BodyPublishers.noBody(), headers);
        SimpleRequest.Builder request = new SimpleRequest.Builder(Request.Method.valueOf(method), rawPath(path));
        headers.forEach(request::withHeader);
        ValidationReport report = INTERNAL.validate(request.build(), toValidated(response));
        if (report.hasErrors()) {
            throw new AssertionError("内部接口不符合规范：" + report.getMessages() + "\n" + response.body());
        }
        return reply(response);
    }

    /**
     * 已按规范校验过的公开 API 操作。
     *
     * @return 操作
     */
    public static Set<String> validatedOperations() {
        return new TreeSet<>(VALIDATED);
    }

    private HttpResponse<String> send(
            String method, String path, HttpRequest.BodyPublisher body, Map<String, String> headers) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(baseUrl + path)).method(method, body);
        headers.forEach(builder::header);
        try {
            return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void part(ByteArrayOutputStream body, String boundary, String headers, byte[] content) {
        body.writeBytes(("--" + boundary + "\r\n" + headers + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static SimpleResponse toValidated(HttpResponse<String> response) {
        SimpleResponse.Builder validated = SimpleResponse.Builder.status(response.statusCode());
        response.headers().map().forEach(validated::withHeader);
        if (!response.body().isEmpty()) {
            validated.withBody(response.body());
        }
        return validated.build();
    }

    private static void check(String operation, ValidationReport report, HttpResponse<String> response) {
        if (report.hasErrors()) {
            throw new AssertionError(operation + " 不符合规范：" + report.getMessages() + "\n" + response.body());
        }
        VALIDATED.add(operation);
    }

    private static String rawPath(String path) {
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    private static Map<String, String> query(String path) {
        Map<String, String> params = new LinkedHashMap<>();
        int query = path.indexOf('?');
        if (query >= 0) {
            for (String pair : path.substring(query + 1).split("&")) {
                String[] parts = pair.split("=", 2);
                params.put(parts[0], parts.length > 1 ? parts[1] : "");
            }
        }
        return params;
    }

    private static Reply reply(HttpResponse<String> response) {
        JsonNode json = response.body().isEmpty() ? null : JSON.readTree(response.body());
        return new Reply(response.statusCode(), response.headers().map(), json, response.body());
    }

    /**
     * 响应。
     *
     * @param status 状态码
     * @param headers 响应头
     * @param json JSON 响应体；没有时为空
     * @param raw 原文
     */
    public record Reply(int status, Map<String, List<String>> headers, JsonNode json, String raw) {

        /**
         * 读取顶层的文本字段。
         *
         * @param field 字段名
         * @return 文本
         */
        public String text(String field) {
            return json.get(field).asString();
        }

        /**
         * 读取响应头。
         *
         * @param name 名字（不区分大小写）
         * @return 第一个值；没有时为空
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
