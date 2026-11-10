package cn.refinex.meridian.runtime.support;

import cn.refinex.meridian.common.json.JsonMappers;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;

/**
 * 公开 API 的测试客户端：以一个 API Key 调用运行中的 runtime，每次交互都按规范校验请求与响应（{@link ContractValidator}）。
 *
 * <p>操作名（例如 {@code POST /v1/sessions/{session_id}:archive}）与规范中的路径模板一致，用于统计契约测试的覆盖。
 */
public final class ApiTestClient {

    private final WebTestClient client;
    private final String apiKey;
    private final Map<String, String> defaultHeaders;

    /**
     * 创建客户端。
     *
     * @param port runtime 的端口
     * @param apiKey API Key 明文；可为空，表示不带认证
     */
    public ApiTestClient(int port, String apiKey) {
        this(port, apiKey, Map.of());
    }

    /**
     * 创建带固定请求头的客户端，例如控制台用户的 OIDC 令牌加 {@code Meridian-Project}（ADR-0044）。
     *
     * @param port runtime 的端口
     * @param bearer {@code Authorization: Bearer} 的值（API Key 或 OIDC 令牌）；可为空
     * @param defaultHeaders 每个请求都带的请求头
     */
    public ApiTestClient(int port, String bearer, Map<String, String> defaultHeaders) {
        this.client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
        this.apiKey = bearer;
        this.defaultHeaders = Map.copyOf(defaultHeaders);
    }

    /**
     * 创建会话。
     *
     * @param body 请求体 JSON
     * @param idempotencyKey 幂等键；可为空
     * @return 响应
     */
    public ApiResponse createSession(String body, String idempotencyKey) {
        return call("POST /v1/sessions", HttpMethod.POST, "/v1/sessions", body, idempotencyKey);
    }

    /**
     * 列出会话。
     *
     * @param query 查询串（不含 {@code ?}）；可为空
     * @return 响应
     */
    public ApiResponse listSessions(String query) {
        return call("GET /v1/sessions", HttpMethod.GET, withQuery("/v1/sessions", query), null, null);
    }

    /**
     * 查询会话。
     *
     * @param sessionId 会话 ID
     * @return 响应
     */
    public ApiResponse getSession(String sessionId) {
        return call("GET /v1/sessions/{session_id}", HttpMethod.GET, "/v1/sessions/" + sessionId, null, null);
    }

    /**
     * 更新会话。
     *
     * @param sessionId 会话 ID
     * @param body 请求体 JSON
     * @return 响应
     */
    public ApiResponse updateSession(String sessionId, String body) {
        return call("PATCH /v1/sessions/{session_id}", HttpMethod.PATCH, "/v1/sessions/" + sessionId, body, null);
    }

    /**
     * 删除会话。
     *
     * @param sessionId 会话 ID
     * @return 响应
     */
    public ApiResponse deleteSession(String sessionId) {
        return call("DELETE /v1/sessions/{session_id}", HttpMethod.DELETE, "/v1/sessions/" + sessionId, null, null);
    }

    /**
     * 归档会话。
     *
     * @param sessionId 会话 ID
     * @param idempotencyKey 幂等键；可为空
     * @return 响应
     */
    public ApiResponse archiveSession(String sessionId, String idempotencyKey) {
        return call(
                "POST /v1/sessions/{session_id}:archive",
                HttpMethod.POST,
                "/v1/sessions/" + sessionId + ":archive",
                null,
                idempotencyKey);
    }

    /**
     * 发送事件。
     *
     * @param sessionId 会话 ID
     * @param body 请求体 JSON
     * @param idempotencyKey 幂等键；可为空
     * @return 响应
     */
    public ApiResponse sendEvents(String sessionId, String body, String idempotencyKey) {
        return call(
                "POST /v1/sessions/{session_id}/events",
                HttpMethod.POST,
                "/v1/sessions/" + sessionId + "/events",
                body,
                idempotencyKey);
    }

    /**
     * 发送一条文本消息。
     *
     * @param sessionId 会话 ID
     * @param text 文本
     * @return 响应
     */
    public ApiResponse sendMessage(String sessionId, String text) {
        return sendEvents(
                sessionId,
                "{\"events\":[{\"type\":\"user.message\",\"content\":[{\"type\":\"text\",\"text\":"
                        + JsonMappers.lenient().writeValueAsString(text) + "}]}]}",
                null);
    }

    /**
     * 列出事件。
     *
     * @param sessionId 会话 ID
     * @param query 查询串；可为空
     * @return 响应
     */
    public ApiResponse listEvents(String sessionId, String query) {
        return call(
                "GET /v1/sessions/{session_id}/events",
                HttpMethod.GET,
                withQuery("/v1/sessions/" + sessionId + "/events", query),
                null,
                null);
    }

    /**
     * 列出线程。
     *
     * @param sessionId 会话 ID
     * @param query 查询串；可为空
     * @return 响应
     */
    public ApiResponse listThreads(String sessionId, String query) {
        return call(
                "GET /v1/sessions/{session_id}/threads",
                HttpMethod.GET,
                withQuery("/v1/sessions/" + sessionId + "/threads", query),
                null,
                null);
    }

    /**
     * 列出待处理动作。
     *
     * @param sessionId 会话 ID
     * @param query 查询串；可为空
     * @return 响应
     */
    public ApiResponse listPendingActions(String sessionId, String query) {
        return call(
                "GET /v1/sessions/{session_id}/pending-actions",
                HttpMethod.GET,
                withQuery("/v1/sessions/" + sessionId + "/pending-actions", query),
                null,
                null);
    }

    /**
     * 读取一个会自行结束的事件流（例如已终止的会话：补发完历史事件后服务端关闭连接），校验响应与每一帧。
     *
     * @param operation 操作名
     * @param pathAndQuery 路径与查询串
     * @param lastEventId {@code Last-Event-ID}；可为空
     * @return 收到的帧，按顺序
     */
    public List<SseTestClient.Frame> finiteStream(String operation, String pathAndQuery, String lastEventId) {
        WebTestClient.RequestHeadersSpec<?> spec = client.get()
                .uri(pathAndQuery)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        if (lastEventId != null) {
            spec = spec.header("Last-Event-ID", lastEventId);
        }
        EntityExchangeResult<byte[]> result =
                spec.exchange().expectBody(byte[].class).returnResult();
        String body =
                result.getResponseBody() == null ? "" : new String(result.getResponseBody(), StandardCharsets.UTF_8);
        SimpleResponse.Builder response =
                SimpleResponse.Builder.status(result.getStatus().value()).withBody(body);
        result.getResponseHeaders().forEach((name, values) -> response.withHeader(name, values));
        URI uri = URI.create(pathAndQuery);
        SimpleRequest.Builder request = new SimpleRequest.Builder(Request.Method.GET, uri.getRawPath())
                .withHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .withHeader(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
        if (lastEventId != null) {
            request.withHeader("Last-Event-ID", lastEventId);
        }
        queryParams(uri).forEach(request::withQueryParam);
        // 事件流的响应体是 SSE 文本：响应按状态码与响应头校验，每一帧的 data 按事件信封 schema 校验
        ContractValidator.validateStream(operation, request.build(), response.build());
        List<SseTestClient.Frame> frames = new ArrayList<>();
        for (String block : body.split("\n\n")) {
            String event = null;
            String id = null;
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    event = line.substring(6).strip();
                } else if (line.startsWith("id:")) {
                    id = line.substring(3).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).strip());
                }
            }
            if (event != null) {
                ContractValidator.validateEvent(data.toString(), id != null);
                frames.add(new SseTestClient.Frame(event, id, data.toString()));
            }
        }
        return frames;
    }

    /**
     * 发出一个请求并校验。
     *
     * @param operation 操作名（规范中的路径模板）
     * @param method HTTP 方法
     * @param pathAndQuery 路径与查询串
     * @param body 请求体 JSON；可为空
     * @param idempotencyKey 幂等键；可为空
     * @return 响应
     */
    public ApiResponse call(
            String operation, HttpMethod method, String pathAndQuery, String body, String idempotencyKey) {
        return call(operation, method, pathAndQuery, body, idempotencyKey, Map.of(), true);
    }

    /**
     * 发出一个请求，只校验响应：请求本身故意不合法时使用（缺少认证、字段错误、未知字段）。
     *
     * @param operation 操作名
     * @param method HTTP 方法
     * @param pathAndQuery 路径与查询串
     * @param body 请求体 JSON；可为空
     * @param headers 额外的请求头
     * @return 响应
     */
    public ApiResponse callInvalid(
            String operation, HttpMethod method, String pathAndQuery, String body, Map<String, String> headers) {
        return call(operation, method, pathAndQuery, body, null, headers, false);
    }

    private ApiResponse call(
            String operation,
            HttpMethod method,
            String pathAndQuery,
            String body,
            String idempotencyKey,
            Map<String, String> extraHeaders,
            boolean validateRequest) {
        Map<String, String> headers = new LinkedHashMap<>(defaultHeaders);
        headers.putAll(extraHeaders);
        if (apiKey != null) {
            headers.putIfAbsent(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        if (idempotencyKey != null) {
            headers.put("Idempotency-Key", idempotencyKey);
        }
        WebTestClient.RequestBodySpec spec = client.method(method).uri(pathAndQuery);
        headers.forEach(spec::header);
        WebTestClient.RequestHeadersSpec<?> request = body == null
                ? spec
                : spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        EntityExchangeResult<byte[]> result =
                request.exchange().expectBody(byte[].class).returnResult();

        int status = result.getStatus().value();
        String responseBody =
                result.getResponseBody() == null ? null : new String(result.getResponseBody(), StandardCharsets.UTF_8);
        SimpleResponse.Builder response = SimpleResponse.Builder.status(status);
        result.getResponseHeaders().forEach((name, values) -> response.withHeader(name, values));
        if (responseBody != null && !responseBody.isEmpty()) {
            response.withBody(responseBody);
        }
        URI uri = URI.create(pathAndQuery);
        Request.Method validatorMethod = Request.Method.valueOf(method.name());
        if (validateRequest) {
            SimpleRequest.Builder validatorRequest = new SimpleRequest.Builder(validatorMethod, uri.getRawPath());
            headers.forEach(validatorRequest::withHeader);
            if (body != null) {
                validatorRequest
                        .withContentType(MediaType.APPLICATION_JSON_VALUE)
                        .withBody(body);
            }
            queryParams(uri).forEach(validatorRequest::withQueryParam);
            ContractValidator.validate(operation, validatorRequest.build(), response.build());
        } else {
            ContractValidator.validateResponse(operation, uri.getRawPath(), validatorMethod, response.build());
        }
        JsonNode json = responseBody == null || responseBody.isEmpty()
                ? null
                : JsonMappers.lenient().readTree(responseBody);
        return new ApiResponse(status, result.getResponseHeaders(), json);
    }

    private static String withQuery(String path, String query) {
        return query == null || query.isEmpty() ? path : path + "?" + query;
    }

    private static Map<String, String> queryParams(URI uri) {
        Map<String, String> params = new LinkedHashMap<>();
        if (uri.getRawQuery() == null) {
            return params;
        }
        for (String pair : uri.getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            params.put(
                    URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8),
                    eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    /**
     * 一次响应。
     *
     * @param status 状态码
     * @param headers 响应头
     * @param body 响应体 JSON；没有响应体时为空
     */
    public record ApiResponse(int status, HttpHeaders headers, JsonNode body) {

        /**
         * 读取响应体中的一个字符串字段。
         *
         * @param field 字段名
         * @return 字段值
         */
        public String text(String field) {
            return body.get(field).asString();
        }
    }
}
