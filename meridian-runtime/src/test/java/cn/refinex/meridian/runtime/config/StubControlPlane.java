package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.security.apikey.ApiKeyHashes;
import cn.refinex.meridian.security.servicejwt.ServiceJwtVerifier;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import cn.refinex.meridian.security.servicejwt.ServicePrincipal;
import cn.refinex.meridian.testsupport.auth.TestTokenIssuer;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;

/**
 * control-plane 的替身（ADR-0044、ADR-0045）：建在 {@link TestTokenIssuer} 上——令牌端点与 JWKS 是真实的签发方，内部接口（API Key 校验、
 * 租户配额、控制台用户的项目权限、模型目录、凭据解析）只接受 runtime 换取的、{@code aud} 为 control-plane 的服务 JWT，响应格式与
 * {@code meridian-internal-v1.yaml} 一致。control-plane 一侧的实现由它自己的集成测试覆盖。
 */
final class StubControlPlane implements AutoCloseable {

    /** runtime 的客户端密钥，只用于测试。 */
    static final String RUNTIME_SECRET = "test-runtime-client-secret";

    final Map<String, Integer> quotas = new ConcurrentHashMap<>();
    final AtomicInteger verifyCalls = new AtomicInteger();
    final AtomicInteger quotaCalls = new AtomicInteger();
    final AtomicInteger modelCalls = new AtomicInteger();
    final AtomicInteger secretResolutions = new AtomicInteger();
    final List<String> callers = new CopyOnWriteArrayList<>();
    private final Map<String, String> verifications = new ConcurrentHashMap<>();
    private final Map<String, String> keyIds = new ConcurrentHashMap<>();
    private final Map<String, String> access = new ConcurrentHashMap<>();
    private final Map<String, CatalogStub> models = new ConcurrentHashMap<>();
    private final Map<String, SecretStub> secrets = new ConcurrentHashMap<>();
    private final TestTokenIssuer issuer;
    private final ServiceJwtVerifier verifier;

    private StubControlPlane(TestTokenIssuer issuer) {
        this.issuer = issuer;
        try {
            this.verifier = ServiceJwtVerifier.remote(
                    URI.create(issuer.jwksUrl()).toURL(),
                    ServiceJwts.DEFAULT_ISSUER,
                    ServiceJwts.CONTROL_PLANE,
                    Duration.ofMinutes(5));
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    static StubControlPlane start() {
        TestTokenIssuer issuer = TestTokenIssuer.start().client(ServiceJwts.RUNTIME, RUNTIME_SECRET);
        StubControlPlane stub = new StubControlPlane(issuer);
        issuer.route("/internal/v1/api-keys:verify", stub::verify)
                .route("/internal/v1/tenants/", stub::quota)
                .route("/internal/v1/project-access:resolve", stub::projectAccess)
                .route("/internal/v1/models/", stub::model)
                .route("/internal/v1/secrets:resolve", stub::secret);
        return stub;
    }

    String baseUrl() {
        return issuer.url();
    }

    String issue(String tenantId, int ratePerMinute, String... scopes) {
        return issue(tenantId, Ids.newId(IdPrefix.PROJECT), ratePerMinute, scopes);
    }

    String issue(String tenantId, String projectId, int ratePerMinute, String... scopes) {
        String plaintext = "mk_live_" + Ids.newUlid() + "abcdefghijklmn";
        String keyId = Ids.newId(IdPrefix.API_KEY);
        verifications.put(
                ApiKeyHashes.sha256(plaintext), """
                {"api_key_id":"%s","tenant_id":"%s","project_id":"%s","scopes":[%s],"rate_limit_per_minute":%d,
                 "expires_at":null}
                """.formatted(keyId, tenantId, projectId, json(scopes), ratePerMinute));
        keyIds.put(plaintext, keyId);
        return plaintext;
    }

    /** 登记控制台用户在项目上的作用域（control-plane 按角色矩阵换算后的结果）。 */
    void grant(String subject, String tenantId, String projectId, String... scopes) {
        access.put(subject + "|" + projectId, """
                {"tenant_id":"%s","project_id":"%s","scopes":[%s]}
                """.formatted(tenantId, projectId, json(scopes)));
    }

    String keyId(String plaintext) {
        return keyIds.get(plaintext);
    }

    void revoke(String plaintext) {
        verifications.remove(ApiKeyHashes.sha256(plaintext));
    }

    /** 登记或替换目录中的一个模型。 */
    void model(CatalogStub model) {
        models.put(model.modelId(), model);
    }

    /** 从目录中删除一个模型。 */
    void removeModel(String modelId) {
        models.remove(modelId);
    }

    /** 读取目录中的一个模型。 */
    CatalogStub model(String modelId) {
        return models.get(modelId);
    }

    /** 登记或替换一个凭据（轮换时版本加一）。 */
    void secret(String secretId, String tenantId, String projectId, String value) {
        SecretStub previous = secrets.get(secretId);
        secrets.put(
                secretId, new SecretStub(tenantId, projectId, value, previous == null ? 1 : previous.version() + 1));
    }

    private static String json(String... values) {
        return String.join(",", Arrays.stream(values).map(s -> "\"" + s + "\"").toList());
    }

    private void verify(HttpExchange exchange) throws IOException {
        verifyCalls.incrementAndGet();
        if (!authorized(exchange)) {
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String hash = body.replaceAll(".*\"key_hash\"\\s*:\\s*\"([0-9a-f]{64})\".*", "$1");
        String verification = verifications.get(hash);
        if (verification == null) {
            respond(exchange, 404, problem("api_key.not_found", 404));
        } else {
            respond(exchange, 200, verification);
        }
    }

    private void quota(HttpExchange exchange) throws IOException {
        quotaCalls.incrementAndGet();
        if (!authorized(exchange)) {
            return;
        }
        String tenantId = exchange.getRequestURI().getPath().split("/")[4];
        Integer quota = quotas.get(tenantId);
        if (quota == null) {
            respond(exchange, 404, problem("tenant.not_found", 404));
        } else {
            respond(exchange, 200, "{\"tenant_id\":\"" + tenantId + "\",\"max_concurrent_turns\":" + quota + "}");
        }
    }

    private void projectAccess(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String subject = body.replaceAll(".*\"subject\"\\s*:\\s*\"([^\"]*)\".*", "$1");
        String project = body.replaceAll(".*\"project_id\"\\s*:\\s*\"([^\"]*)\".*", "$1");
        String granted = access.get(subject + "|" + project);
        if (granted == null) {
            respond(exchange, 404, problem("project.not_found", 404));
        } else {
            respond(exchange, 200, granted);
        }
    }

    /** {@code GET /internal/v1/models/{model_id}}。 */
    private void model(HttpExchange exchange) throws IOException {
        modelCalls.incrementAndGet();
        if (!authorized(exchange)) {
            return;
        }
        String modelId = exchange.getRequestURI().getPath().split("/")[4];
        CatalogStub model = models.get(modelId);
        if (model == null) {
            respond(exchange, 404, problem("catalog.model_not_found", 404));
        } else {
            respond(exchange, 200, model.json());
        }
    }

    /** {@code POST /internal/v1/secrets:resolve}：租户必须一致，项目级凭据还要求项目一致。 */
    private void secret(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        JsonNode body = JsonMappers.lenient().readTree(exchange.getRequestBody().readAllBytes());
        String secretId = body.path("secret_id").asString();
        String projectId = body.path("project_id").isNull()
                ? null
                : body.path("project_id").asString(null);
        SecretStub secret = secrets.get(secretId);
        if (secret == null
                || !secret.tenantId().equals(body.path("tenant_id").asString())
                || (secret.projectId() != null && !secret.projectId().equals(projectId))) {
            respond(exchange, 404, problem("secret.not_found", 404));
            return;
        }
        secretResolutions.incrementAndGet();
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        respond(
                exchange,
                200,
                JsonMappers.lenient()
                        .writeValueAsString(
                                Map.of("secret_id", secretId, "version", secret.version(), "value", secret.value())));
    }

    /** 只接受 runtime 的服务 JWT（签名、签发方、受众都由真实的校验器检查）。 */
    private boolean authorized(HttpExchange exchange) throws IOException {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        Optional<ServicePrincipal> principal = header != null && header.startsWith("Bearer ")
                ? verifier.resolve(header.substring("Bearer ".length()))
                : Optional.empty();
        if (principal.isPresent() && principal.get().service().equals(ServiceJwts.RUNTIME)) {
            callers.add(principal.get().service());
            return true;
        }
        respond(exchange, 401, problem("auth.invalid_service_credential", 401));
        return false;
    }

    private static String problem(String code, int status) {
        return "{\"type\":\"urn:meridian:error:" + code + "\",\"title\":\"x\",\"status\":" + status
                + ",\"detail\":null,\"instance\":\"/\",\"code\":\"" + code + "\",\"request_id\":\"r\"}";
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

    /**
     * 目录中的一个对话模型与它的 OpenAI 兼容供应商账号（{@code CatalogModel} 的形状）。
     *
     * @param modelId 模型 ID
     * @param tenantId 所属租户
     * @param accountId 供应商账号 ID
     * @param baseUrl 账号的端点
     * @param secretId 账号的凭据 ID；可为空
     * @param priceBookId 价格表；可为空
     * @param priceBookVersion 价格表当前版本；可为空
     * @param upstreamModel 供应商侧的模型名
     */
    record CatalogStub(
            String modelId,
            String tenantId,
            String accountId,
            String baseUrl,
            String secretId,
            String priceBookId,
            Integer priceBookVersion,
            String upstreamModel) {

        /** 供应商侧模型名为 {@code scripted}（脚本化模型服务与 MockWebServer 都不看它）。 */
        CatalogStub(
                String modelId,
                String tenantId,
                String accountId,
                String baseUrl,
                String secretId,
                String priceBookId,
                Integer priceBookVersion) {
            this(modelId, tenantId, accountId, baseUrl, secretId, priceBookId, priceBookVersion, "scripted");
        }

        CatalogStub withBaseUrl(String value) {
            return new CatalogStub(
                    modelId, tenantId, accountId, value, secretId, priceBookId, priceBookVersion, upstreamModel);
        }

        CatalogStub withPriceBookVersion(int value) {
            return new CatalogStub(modelId, tenantId, accountId, baseUrl, secretId, priceBookId, value, upstreamModel);
        }

        String json() {
            Map<String, Object> account = new java.util.LinkedHashMap<>();
            account.put("provider_account_id", accountId);
            account.put("provider", "openai_compatible");
            account.put("base_url", baseUrl);
            account.put("secret_id", secretId);
            account.put("rate_limit", rateLimit());
            Map<String, Object> model = new java.util.LinkedHashMap<>();
            model.put("model_id", modelId);
            model.put("tenant_id", tenantId);
            model.put("kind", "chat");
            model.put("upstream_model", upstreamModel);
            model.put("context_window", 131072);
            model.put("max_output_tokens", 1024);
            model.put("fallback_model_id", null);
            model.put("base_url", null);
            model.put("max_batch_size", null);
            model.put("rate_limit", rateLimit());
            model.put("price_book_id", priceBookId);
            model.put("price_book_version", priceBookVersion);
            model.put("provider_account", account);
            return JsonMappers.lenient().writeValueAsString(model);
        }

        private static Map<String, Object> rateLimit() {
            Map<String, Object> limit = new java.util.LinkedHashMap<>();
            limit.put("rpm", null);
            limit.put("tpm", null);
            return limit;
        }
    }

    /** 一个凭据。 */
    private record SecretStub(String tenantId, String projectId, String value, int version) {}
}
