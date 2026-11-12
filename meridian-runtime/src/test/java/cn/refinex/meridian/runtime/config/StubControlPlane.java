package cn.refinex.meridian.runtime.config;

import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.engine.spi.spec.EnvironmentSpec;
import cn.refinex.meridian.engine.spi.spec.ResolvedAgentSpec;
import cn.refinex.meridian.engine.spi.spec.SkillDefinition;
import cn.refinex.meridian.runtime.stream.infra.EngineJson;
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
 * control-plane 的替身（ADR-0044、ADR-0045、ADR-0047）：建在 {@link TestTokenIssuer} 上——令牌端点与 JWKS 是真实的签发方，内部接口（API
 * Key 校验、租户配额与设置、控制台用户的项目权限、模型目录、凭据解析、智能体版本与环境的解析、技能内容、租户与项目策略）只接受 runtime
 * 换取的、{@code aud} 为 control-plane 的服务 JWT，响应格式与 {@code meridian-internal-v1.yaml} 一致。control-plane 一侧的实现由它自己的
 * 集成测试覆盖。
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
    final AtomicInteger configCalls = new AtomicInteger();
    final List<String> rememberedRules = new CopyOnWriteArrayList<>();
    private final Map<String, String> agentVersions = new ConcurrentHashMap<>();
    private final Map<String, String> currentAgents = new ConcurrentHashMap<>();
    private final Map<String, String> environments = new ConcurrentHashMap<>();
    private final Map<String, Long> currentEnvironments = new ConcurrentHashMap<>();
    private final Map<String, String> skills = new ConcurrentHashMap<>();
    private final Map<String, String> tenantSettings = new ConcurrentHashMap<>();
    private final Map<String, Policies> policies = new ConcurrentHashMap<>();
    private final Map<String, String> projects = new ConcurrentHashMap<>();
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
                .route("/internal/v1/tenants/", stub::tenant)
                .route("/internal/v1/project-access:resolve", stub::projectAccess)
                .route("/internal/v1/models/", stub::model)
                .route("/internal/v1/secrets:resolve", stub::secret)
                .route("/internal/v1/agent-versions/", stub::agentVersion)
                .route("/internal/v1/agents/", stub::currentAgent)
                .route("/internal/v1/environments/", stub::environment)
                .route("/internal/v1/skills/", stub::skill)
                .route("/internal/v1/projects/", stub::permissionRules);
        return stub;
    }

    String baseUrl() {
        return issuer.url();
    }

    String issue(String tenantId, int ratePerMinute, String... scopes) {
        return issue(tenantId, Ids.newId(IdPrefix.PROJECT), ratePerMinute, scopes);
    }

    String issue(String tenantId, String projectId, int ratePerMinute, String... scopes) {
        projects.put(projectId, tenantId);
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

    /**
     * 发布一个智能体版本（{@code ResolvedAgentVersion} 的形状）：它成为该智能体的当前版本，技能内容随解析响应给出。
     *
     * @param tenantId 所属租户
     * @param projectId 所属项目
     * @param spec 规格，按 runtime 的 JSON 约定写出
     * @param skillContents 引用的技能内容
     */
    void publish(String tenantId, String projectId, ResolvedAgentSpec spec, SkillDefinition... skillContents) {
        Map<String, Object> version = new java.util.LinkedHashMap<>();
        version.put("tenant_id", tenantId);
        version.put("project_id", projectId);
        version.put("agent_id", spec.agentId());
        version.put("agent_version_id", spec.agentVersionId());
        version.put("version", 1);
        version.put("agent_deleted", false);
        version.put("spec", JsonMappers.lenient().readTree(EngineJson.write(spec)));
        version.put(
                "skills",
                Arrays.stream(skillContents)
                        .map(skill -> skillJson(tenantId, projectId, skill))
                        .toList());
        agentVersions.put(spec.agentVersionId(), JsonMappers.lenient().writeValueAsString(version));
        currentAgents.put(spec.agentId(), spec.agentVersionId());
        for (SkillDefinition skill : skillContents) {
            skills.put(
                    skill.skillId() + "#" + skill.version(),
                    JsonMappers.lenient().writeValueAsString(skillJson(tenantId, projectId, skill)));
        }
    }

    /**
     * 登记环境的一个修订，修订号最大的是当前修订。
     *
     * @param tenantId 所属租户
     * @param projectId 所属项目
     * @param spec 环境配置
     */
    void environment(String tenantId, String projectId, EnvironmentSpec spec) {
        Map<String, Object> environment = new java.util.LinkedHashMap<>();
        environment.put("tenant_id", tenantId);
        environment.put("project_id", projectId);
        environment.put("environment_id", spec.environmentId());
        environment.put("revision", spec.revision());
        environment.put("tier", spec.tier().value());
        environment.put("size", spec.size() == null ? null : spec.size().value());
        environment.put("egress_allowlist", spec.egressAllowlist());
        environments.put(
                spec.environmentId() + "#" + spec.revision(),
                JsonMappers.lenient().writeValueAsString(environment));
        currentEnvironments.merge(spec.environmentId(), spec.revision(), Math::max);
    }

    /**
     * 设置租户设置。
     *
     * @param tenantId 租户
     * @param systemPrompt 租户提示词
     * @param webSearch 联网搜索后端（{@code backend}、{@code base_url}、{@code secret_id}）；可为空
     */
    void tenantSettings(String tenantId, String systemPrompt, Map<String, Object> webSearch) {
        Map<String, Object> settings = new java.util.LinkedHashMap<>();
        settings.put("tenant_id", tenantId);
        settings.put("system_prompt", systemPrompt);
        settings.put("web_search", webSearch);
        tenantSettings.put(tenantId, JsonMappers.lenient().writeValueAsString(settings));
    }

    /**
     * 设置一个项目的租户策略与项目策略。
     *
     * @param tenantId 项目所属租户
     * @param projectId 项目
     * @param tenantRules 租户策略：{@code [工具, 内容, 结果]}，内容可为空
     * @param projectRules 项目策略
     */
    void policies(String tenantId, String projectId, List<String[]> tenantRules, List<String[]> projectRules) {
        policies.put(
                projectId,
                new Policies(
                        tenantId, new CopyOnWriteArrayList<>(tenantRules), new CopyOnWriteArrayList<>(projectRules)));
    }

    private static Map<String, Object> skillJson(String tenantId, String projectId, SkillDefinition skill) {
        Map<String, Object> content = new java.util.LinkedHashMap<>();
        content.put("tenant_id", tenantId);
        content.put("project_id", projectId);
        content.put("skill_id", skill.skillId());
        content.put("version", skill.version());
        content.put("name", skill.name());
        content.put("files", skill.files());
        return content;
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

    /** {@code /internal/v1/tenants/{id}/quotas} 与 {@code /internal/v1/tenants/{id}/settings}。 */
    private void tenant(HttpExchange exchange) throws IOException {
        if (exchange.getRequestURI().getPath().endsWith("/settings")) {
            configCalls.incrementAndGet();
            if (!authorized(exchange)) {
                return;
            }
            String tenantId = exchange.getRequestURI().getPath().split("/")[4];
            respond(
                    exchange,
                    200,
                    tenantSettings.getOrDefault(
                            tenantId,
                            "{\"tenant_id\":\"" + tenantId + "\",\"system_prompt\":\"\",\"web_search\":null}"));
            return;
        }
        quota(exchange);
    }

    /** {@code POST /internal/v1/agent-versions/{id}:resolve}。 */
    private void agentVersion(HttpExchange exchange) throws IOException {
        configCalls.incrementAndGet();
        if (!authorized(exchange)) {
            return;
        }
        String id = exchange.getRequestURI().getPath().split("/")[4].replace(":resolve", "");
        String version = agentVersions.get(id);
        respond(
                exchange,
                version == null ? 404 : 200,
                version == null ? problem("agent.version_not_found", 404) : version);
    }

    /** {@code POST /internal/v1/agents/{id}:resolve}。 */
    private void currentAgent(HttpExchange exchange) throws IOException {
        configCalls.incrementAndGet();
        if (!authorized(exchange)) {
            return;
        }
        String id = exchange.getRequestURI().getPath().split("/")[4].replace(":resolve", "");
        String versionId = currentAgents.get(id);
        if (versionId == null) {
            respond(exchange, 404, problem("agent.not_found", 404));
        } else {
            respond(exchange, 200, agentVersions.get(versionId));
        }
    }

    /** {@code GET /internal/v1/environments/{id}[?revision=]}。 */
    private void environment(HttpExchange exchange) throws IOException {
        configCalls.incrementAndGet();
        if (!authorized(exchange)) {
            return;
        }
        String id = exchange.getRequestURI().getPath().split("/")[4];
        String query = exchange.getRequestURI().getQuery();
        Long revision = query != null && query.startsWith("revision=")
                ? Long.valueOf(query.substring("revision=".length()))
                : currentEnvironments.get(id);
        String environment = revision == null ? null : environments.get(id + "#" + revision);
        respond(
                exchange,
                environment == null ? 404 : 200,
                environment == null ? problem("environment.not_found", 404) : environment);
    }

    /** {@code GET /internal/v1/skills/{id}/versions/{version}}。 */
    private void skill(HttpExchange exchange) throws IOException {
        configCalls.incrementAndGet();
        if (!authorized(exchange)) {
            return;
        }
        String[] parts = exchange.getRequestURI().getPath().split("/");
        String skill = skills.get(parts[4] + "#" + parts[6]);
        respond(exchange, skill == null ? 404 : 200, skill == null ? problem("skill.version_not_found", 404) : skill);
    }

    /** {@code GET|POST /internal/v1/projects/{id}/permission-rules}：记住此决定追加到项目策略（相同的不重复）。 */
    private void permissionRules(HttpExchange exchange) throws IOException {
        configCalls.incrementAndGet();
        if (!authorized(exchange)) {
            return;
        }
        String projectId = exchange.getRequestURI().getPath().split("/")[4];
        // 签发过 Key 的项目都存在，没有设置策略时两层都为空
        Policies policy = projects.containsKey(projectId)
                ? policies.computeIfAbsent(
                        projectId,
                        id -> new Policies(
                                projects.get(id), new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>()))
                : null;
        if (policy == null) {
            respond(exchange, 404, problem("project.not_found", 404));
            return;
        }
        if ("POST".equals(exchange.getRequestMethod())) {
            JsonNode body =
                    JsonMappers.lenient().readTree(exchange.getRequestBody().readAllBytes());
            String[] rule = {
                body.path("tool_name").asString(),
                body.path("rule_content").isNull()
                        ? null
                        : body.path("rule_content").asString(null),
                body.path("behavior").asString()
            };
            rememberedRules.add(body.toString());
            if (policy.projectRules().stream().noneMatch(r -> Arrays.equals(r, rule))) {
                policy.projectRules().add(rule);
            }
            respond(exchange, 200, ruleJson(rule, "remembered"));
            return;
        }
        respond(
                exchange,
                200,
                "{\"tenant_id\":\"" + policy.tenantId() + "\",\"project_id\":\"" + projectId
                        + "\",\"tenant_rules\":[" + rulesJson(policy.tenantRules()) + "],\"project_rules\":["
                        + rulesJson(policy.projectRules()) + "]}");
    }

    private static String rulesJson(List<String[]> rules) {
        return String.join(",", rules.stream().map(r -> ruleJson(r, "policy")).toList());
    }

    private static String ruleJson(String[] rule, String source) {
        Map<String, Object> json = new java.util.LinkedHashMap<>();
        json.put("id", Ids.newId(IdPrefix.PERMISSION_RULE));
        json.put("tool_name", rule[0]);
        json.put("rule_content", rule[1]);
        json.put("behavior", rule[2]);
        json.put("source", source);
        return JsonMappers.lenient().writeValueAsString(json);
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

    /** 一个项目的两层策略，规则是 {@code [工具, 内容, 结果]}。 */
    private record Policies(String tenantId, List<String[]> tenantRules, List<String[]> projectRules) {}
}
