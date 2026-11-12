package cn.refinex.meridian.knowledge.support;

import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.security.apikey.ApiKeyHashes;
import cn.refinex.meridian.security.servicejwt.ServiceJwtVerifier;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import cn.refinex.meridian.security.servicejwt.ServicePrincipal;
import cn.refinex.meridian.testsupport.auth.TestTokenIssuer;
import cn.refinex.meridian.testsupport.model.ScriptedVectors;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * knowledge 集成测试中的 control-plane 与 runtime 替身，建在 {@link TestTokenIssuer} 上：真实的令牌端点与 JWKS，knowledge 用自己的
 * 客户端密钥换取服务 JWT。
 *
 * <ul>
 *   <li>control-plane：{@code POST /internal/v1/api-keys:verify}（只认 {@code aud=control-plane} 的 knowledge 令牌），{@link #issue}
 *       签发 Key、{@link #revoke} 吊销。
 *   <li>runtime：{@code POST /internal/v1/embeddings}（只认 {@code aud=runtime} 的 knowledge 令牌），按文本哈希给出确定的向量
 *       （{@link ScriptedVectors}），记下每次调用；{@link #failNext} 让接下来的调用返回指定的状态码，模拟依赖暂时不可用。
 * </ul>
 */
public final class StubPlatform implements AutoCloseable {

    /** knowledge 的客户端密钥。 */
    public static final String KNOWLEDGE_SECRET = "knowledge-test-client-secret";

    /** runtime 收到的一次 Embedding 调用。 */
    public record EmbeddingCall(String caller, String tenantId, String projectId, String model, int inputs) {}

    private final TestTokenIssuer issuer;
    private final ServiceJwtVerifier controlPlaneVerifier;
    private final ServiceJwtVerifier runtimeVerifier;
    private final Map<String, String> verifications = new ConcurrentHashMap<>();
    private final Map<String, String> keyIds = new ConcurrentHashMap<>();
    private final Deque<Integer> failures = new ConcurrentLinkedDeque<>();
    private final List<EmbeddingCall> embeddingCalls = new CopyOnWriteArrayList<>();
    private final AtomicInteger verifyCalls = new AtomicInteger();
    private volatile Function<List<String>, Embedded> embedder;

    /**
     * 一批 Embedding 的结果。
     *
     * @param vectors 向量
     * @param promptTokens 用量
     */
    public record Embedded(List<float[]> vectors, long promptTokens) {}

    private StubPlatform(TestTokenIssuer issuer) {
        this.issuer = issuer;
        this.controlPlaneVerifier = verifier(issuer, ServiceJwts.CONTROL_PLANE);
        this.runtimeVerifier = verifier(issuer, ServiceJwts.RUNTIME);
    }

    /**
     * 启动替身。
     *
     * @return 替身
     */
    public static StubPlatform start() {
        TestTokenIssuer issuer = TestTokenIssuer.start().client(ServiceJwts.KNOWLEDGE, KNOWLEDGE_SECRET);
        StubPlatform stub = new StubPlatform(issuer);
        issuer.route("/internal/v1/api-keys:verify", stub::verify).route("/internal/v1/embeddings", stub::embed);
        return stub;
    }

    /**
     * 基础 URL：既是 control-plane，也是 runtime。
     *
     * @return URL
     */
    public String url() {
        return issuer.url();
    }

    /**
     * 令牌签发方（测试用它签发 control-plane 调用内部接口的令牌）。
     *
     * @return 签发方
     */
    public TestTokenIssuer issuer() {
        return issuer;
    }

    /**
     * 签发一个 API Key。
     *
     * @param tenantId 租户
     * @param projectId 项目
     * @param scopes 作用域
     * @return Key 明文
     */
    public String issue(String tenantId, String projectId, String... scopes) {
        String plaintext = "mk_live_" + Ids.newUlid() + "abcdefghijklmn";
        String keyId = Ids.newId(IdPrefix.API_KEY);
        StringBuilder json = new StringBuilder();
        for (String scope : scopes) {
            json.append(json.isEmpty() ? "" : ",").append('"').append(scope).append('"');
        }
        verifications.put(ApiKeyHashes.sha256(plaintext), """
                {"api_key_id":"%s","tenant_id":"%s","project_id":"%s","scopes":[%s],"rate_limit_per_minute":100000,
                 "expires_at":null}
                """.formatted(keyId, tenantId, projectId, json));
        keyIds.put(plaintext, keyId);
        return plaintext;
    }

    /**
     * 吊销一个 Key：之后的校验返回 404。
     *
     * @param plaintext Key 明文
     */
    public void revoke(String plaintext) {
        verifications.remove(ApiKeyHashes.sha256(plaintext));
    }

    /**
     * Key 的 ID。
     *
     * @param plaintext Key 明文
     * @return ID
     */
    public String keyId(String plaintext) {
        return keyIds.get(plaintext);
    }

    /**
     * 校验接口被调用的次数。
     *
     * @return 次数
     */
    public int verifyCalls() {
        return verifyCalls.get();
    }

    /**
     * 让接下来的 Embedding 调用依次返回这些状态码（problem+json），之后恢复正常。
     *
     * @param statuses 状态码
     */
    public void failNext(int... statuses) {
        for (int status : statuses) {
            failures.add(status);
        }
    }

    /**
     * 换掉向量的计算（真实模型测试转发给 DashScope）。
     *
     * @param function 文本到向量；为空时恢复为确定的向量
     */
    public void embedder(Function<List<String>, Embedded> function) {
        this.embedder = function;
    }

    /**
     * 收到的 Embedding 调用。
     *
     * @return 调用
     */
    public List<EmbeddingCall> embeddingCalls() {
        return List.copyOf(embeddingCalls);
    }

    private void verify(HttpExchange exchange) throws IOException {
        verifyCalls.incrementAndGet();
        if (authorized(exchange, controlPlaneVerifier).isEmpty()) {
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String verification = verifications.get(
                JsonMappers.lenient().readTree(body).path("key_hash").asString());
        if (verification == null) {
            respond(exchange, 404, problem("api_key.not_found", 404));
        } else {
            respond(exchange, 200, verification);
        }
    }

    private void embed(HttpExchange exchange) throws IOException {
        Optional<ServicePrincipal> caller = authorized(exchange, runtimeVerifier);
        if (caller.isEmpty()) {
            return;
        }
        JsonNode request =
                JsonMappers.lenient().readTree(exchange.getRequestBody().readAllBytes());
        List<String> texts = new ArrayList<>();
        request.path("input").forEach(text -> texts.add(text.asString()));
        embeddingCalls.add(new EmbeddingCall(
                caller.get().service(),
                request.path("tenant_id").asString(null),
                request.path("project_id").asString(null),
                request.path("model").asString(null),
                texts.size()));
        Integer failure = failures.poll();
        if (failure != null) {
            respond(
                    exchange,
                    failure,
                    problem(failure == 404 ? "model.not_found" : "model.provider_unavailable", failure));
            return;
        }
        int dimensions = request.path("dimensions").asInt(ScriptedVectors.DEFAULT_DIMENSIONS);
        Function<List<String>, Embedded> custom = embedder;
        Embedded embedded = custom != null
                ? custom.apply(texts)
                : new Embedded(
                        texts.stream()
                                .map(text -> ScriptedVectors.embed(text, dimensions))
                                .toList(),
                        texts.stream().mapToLong(ScriptedVectors::tokens).sum());
        ObjectNode response = JsonMappers.lenient().createObjectNode();
        ArrayNode data = response.putArray("data");
        for (int i = 0; i < embedded.vectors().size(); i++) {
            ArrayNode values = data.addObject().put("index", i).putArray("embedding");
            for (float value : embedded.vectors().get(i)) {
                values.add(value);
            }
        }
        response.put("model", request.path("model").asString());
        response.putObject("usage")
                .put("prompt_tokens", embedded.promptTokens())
                .put("total_tokens", embedded.promptTokens());
        respond(exchange, 200, JsonMappers.lenient().writeValueAsString(response));
    }

    private static Optional<ServicePrincipal> authorized(HttpExchange exchange, ServiceJwtVerifier verifier)
            throws IOException {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        Optional<ServicePrincipal> principal = header != null && header.startsWith("Bearer ")
                ? verifier.resolve(header.substring("Bearer ".length()))
                : Optional.empty();
        if (principal.isPresent() && principal.get().service().equals(ServiceJwts.KNOWLEDGE)) {
            return principal;
        }
        respond(exchange, 401, problem("auth.invalid_service_credential", 401));
        return Optional.empty();
    }

    private static ServiceJwtVerifier verifier(TestTokenIssuer issuer, String audience) {
        try {
            return ServiceJwtVerifier.remote(
                    URI.create(issuer.jwksUrl()).toURL(), ServiceJwts.DEFAULT_ISSUER, audience, Duration.ofMinutes(5));
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String problem(String code, int status) {
        return "{\"type\":\"urn:meridian:error:" + code + "\",\"title\":\"x\",\"status\":" + status + ",\"detail\":\""
                + code + "\",\"instance\":\"/\",\"code\":\"" + code + "\",\"request_id\":\"r\"}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders()
                .set("Content-Type", status == 200 ? "application/json" : "application/problem+json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        issuer.close();
    }
}
