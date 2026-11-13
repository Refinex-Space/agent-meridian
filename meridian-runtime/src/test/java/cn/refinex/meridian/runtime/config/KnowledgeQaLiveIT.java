package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.common.net.EndpointPolicy;
import cn.refinex.meridian.runtime.RuntimeApplication;
import cn.refinex.meridian.runtime.session.app.TurnWorkerLifecycle;
import cn.refinex.meridian.runtime.support.ApiTestClient;
import cn.refinex.meridian.runtime.support.ApiTestClient.ApiResponse;
import cn.refinex.meridian.runtime.support.ApiTestKeys;
import cn.refinex.meridian.runtime.support.SseTestClient;
import cn.refinex.meridian.runtime.support.SseTestClient.Connection;
import cn.refinex.meridian.security.apikey.ApiKeyHashes;
import cn.refinex.meridian.security.servicejwt.ServiceCredentialProvider;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import cn.refinex.meridian.testsupport.MeridianContainers;
import cn.refinex.meridian.testsupport.SpringContainerProperties;
import cn.refinex.meridian.testsupport.TestDatabase;
import com.sun.net.httpserver.HttpExchange;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;

/**
 * 知识库的端到端问答（T19-A04，{@code -Plive}）：runtime 在本进程中运行（真实的 {@code qwen-plus}、Embedding，DashScope），knowledge 是
 * Jib 镜像 {@code agent-meridian/knowledge:dev} 的容器，两者经服务 JWT 互相调用（knowledge 写入与检索时调 runtime 的 Embedding，智能体的
 * {@code knowledge_search} 调 knowledge 的内部检索）。语料是检索评测中的两份制度：考勤（不限标签）与薪酬（{@code level:confidential}）。
 *
 * <ul>
 *   <li>有 {@code level:confidential} 的会话问 5 个问题：每轮都检索，回答末尾的"来源"都出现在本轮的检索结果中，标准答案所在的文件被检索到。
 *   <li>没有标签的会话问薪酬问题：事件（含工具结果）中不出现薪酬制度的切片，回答非空。
 * </ul>
 *
 * <p>前提：Docker 在运行，设置了 {@code DASHSCOPE_API_KEY}，镜像是当前代码构建的
 * （{@code ./mvnw -pl meridian-knowledge -am -DskipTests package jib:dockerBuild}，或 {@code scripts/dev/build-images.sh}）；没有密钥或
 * 镜像时跳过。真实调用约 20 次（写入 2 次 Embedding；每个问题一次检索的 Embedding 加两次对话）。
 */
@Tag("live")
@SpringBootTest(
        classes = {RuntimeApplication.class, KnowledgeQaLiveIT.Credentials.class},
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
@DirtiesContext
class KnowledgeQaLiveIT {

    private static final String IMAGE = "agent-meridian/knowledge:dev";
    private static final String AGENT = "agt_live_kb_qa";
    private static final String ENVIRONMENT = "env_live_kb";
    private static final String KNOWLEDGE_SECRET = "knowledge-live-client-secret";
    private static final Duration WAIT = Duration.ofSeconds(120);
    private static final Path CORPUS = Path.of("../meridian-knowledge/src/test/resources/retrieval-eval/corpus");
    private static final Pattern DOCUMENT = Pattern.compile("document=\"([^\"]+)\"");
    private static final Set<String> CORPUS_FILES = Set.of("attendance.md", "compensation.md");

    /** 5 个问题与标准答案所在的文件（取自检索评测的 Q16–Q20）。 */
    private static final List<String[]> QUESTIONS = List.of(
            new String[] {"入职 12 年每年有几天年假？", "attendance.md"},
            new String[] {"春节期间加班工资怎么算？", "attendance.md"},
            new String[] {"每天几点之前必须到岗？", "attendance.md"},
            new String[] {"绩效拿到 A 一般涨多少工资？", "compensation.md"},
            new String[] {"年终奖一般发几个月？", "compensation.md"});

    private static final String SYSTEM_PROMPT = """
            你是公司制度问答助手。回答任何制度问题之前，先调用 knowledge_search 检索一次；只根据检索结果回答，检索结果中没有相关内容时，\
            回答"找不到相关信息"。回答简短。回答的最后一行写"来源："，后面列出你引用的检索结果的 document 属性值（文件名原样照抄，\
            多个用顿号分隔）；没有引用时写"来源：无"。""";

    private static final int RUNTIME_PORT = freePort();
    private static final String API_KEY = "mk_live_" + Ids.newUlid() + "abcdefghijklmn";

    private static Path directory;
    private static GenericContainer<?> knowledge;
    private static String knowledgeBase;
    private static TurnWorkerLifecycle worker;

    @Autowired
    private TurnWorkerLifecycle lifecycle;

    @BeforeAll
    static void startKnowledge() throws IOException {
        assumeTrue(
                System.getenv("DASHSCOPE_API_KEY") != null
                        && !System.getenv("DASHSCOPE_API_KEY").isBlank(),
                "没有设置 DASHSCOPE_API_KEY");
        assumeTrue(
                imageExists(),
                "没有镜像 " + IMAGE + "：先 ./mvnw -pl meridian-knowledge -am -DskipTests package jib:dockerBuild");
        // knowledge 经测试的令牌签发方换取服务 JWT、校验 API Key（替身）；runtime 与其他容器都经宿主端口访问
        ApiTestKeys.SERVICE_TOKENS
                .client(ServiceJwts.KNOWLEDGE, KNOWLEDGE_SECRET)
                .route("/internal/v1/api-keys:verify", KnowledgeQaLiveIT::verifyApiKey);
        int issuerPort = URI.create(ApiTestKeys.SERVICE_TOKENS.url()).getPort();
        int postgresPort = MeridianContainers.postgres().getMappedPort(5432);
        int redisPort = MeridianContainers.redis().getMappedPort(6379);
        int minioPort = MeridianContainers.minio().getMappedPort(9000);
        Testcontainers.exposeHostPorts(RUNTIME_PORT, issuerPort, postgresPort, redisPort, minioPort);
        String host = "host.testcontainers.internal";
        String jdbc = TestDatabase.jdbcUrl()
                .replace(MeridianContainers.postgres().getHost() + ":" + postgresPort, host + ":" + postgresPort);
        List<String> options = List.of(
                "-Dspring.datasource.url=" + jdbc,
                "-Dspring.datasource.username=" + MeridianContainers.APP_USER,
                "-Dspring.datasource.password=" + MeridianContainers.APP_PASSWORD,
                "-Dspring.flyway.user=" + MeridianContainers.MIGRATOR_USER,
                "-Dspring.flyway.password=" + MeridianContainers.MIGRATOR_PASSWORD,
                "-Dspring.data.redis.host=" + host,
                "-Dspring.data.redis.port=" + redisPort,
                "-Dmeridian.persistence.objectstore.endpoint=http://" + host + ":" + minioPort,
                "-Dmeridian.persistence.objectstore.region=" + SpringContainerProperties.MINIO_REGION,
                "-Dmeridian.persistence.objectstore.bucket=" + MeridianContainers.MINIO_BUCKET,
                "-Dmeridian.persistence.objectstore.access-key="
                        + MeridianContainers.minio().getUserName(),
                "-Dmeridian.persistence.objectstore.secret-key="
                        + MeridianContainers.minio().getPassword(),
                "-Dmeridian.persistence.objectstore.path-style-access=true",
                "-Dmeridian.security.service-client.control-plane-url=http://" + host + ":" + issuerPort,
                "-Dmeridian.security.service-jwt.client-secret=" + KNOWLEDGE_SECRET,
                "-Dmeridian.knowledge.runtime.url=http://" + host + ":" + RUNTIME_PORT,
                "-Dmeridian.knowledge.storage-metering.enabled=false");
        knowledge = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withEnv("JAVA_TOOL_OPTIONS", String.join(" ", options))
                .withExposedPorts(18083)
                .waitingFor(Wait.forHttp("/actuator/health").forPort(18083).withStartupTimeout(Duration.ofMinutes(2)));
        knowledge.start();
        knowledgeBase = knowledgeApi(
                        "POST", "/v1/knowledge-bases", "{\"name\":\"公司制度\",\"embedding_model\":\"mdl_live_embedding\"}")
                .get("id")
                .asString();
        directory = writeConfiguration(knowledgeBase);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SpringContainerProperties.postgres(registry);
        SpringContainerProperties.redis(registry);
        SpringContainerProperties.minio(registry);
        ApiTestKeys.register(registry);
        registry.add("server.port", () -> RUNTIME_PORT);
        registry.add("meridian.runtime.knowledge.url", () -> "http://127.0.0.1:" + knowledge.getMappedPort(18083));
        registry.add(
                "meridian.runtime.dev-agent-config.directory",
                () -> directory.resolve("agents").toString());
        registry.add(
                "meridian.runtime.model-routing.providers-directory",
                () -> directory.resolve("providers").toString());
        registry.add(
                "meridian.runtime.model-routing.budgets-directory",
                () -> directory.resolve("budgets").toString());
        // fake-ip 透明代理把公网域名解析到 198.18.0.0/15（本机如此）：显式放行，其余与默认策略相同
        List<String> networks = new ArrayList<>(EndpointPolicy.DEFAULT_PRIVATE_NETWORKS);
        networks.add("198.18.0.0/15");
        for (int i = 0; i < networks.size(); i++) {
            String network = networks.get(i);
            registry.add("meridian.runtime.model-routing.private-networks[" + i + "]", () -> network);
        }
    }

    @AfterAll
    static void stop() {
        if (worker != null) {
            worker.stop();
        }
        GracefulShutdownManager.getInstance().resetForTesting();
        if (knowledge != null) {
            knowledge.stop();
        }
    }

    @Test
    void answersCiteTheRetrievedSourcesAndRestrictedPassagesStayHidden() throws IOException {
        worker = lifecycle;
        ingest("attendance.md", null);
        ingest("compensation.md", "level:confidential");

        String allowed = createSession("[\"level:confidential\"]");
        for (String[] question : QUESTIONS) {
            Turn turn = ask(allowed, question[0]);
            System.out.printf("问：%s%n答：%s%n检索到：%s%n%n", question[0], turn.answer(), turn.retrieved());
            assertThat(turn.retrieved())
                    .as("本轮调用了 knowledge_search 且检索到标准答案所在的文件")
                    .contains(question[1]);
            assertThat(turn.answer()).isNotBlank();
            Set<String> cited = turn.cited();
            assertThat(cited).as("回答引用了来源：" + turn.answer()).isNotEmpty();
            assertThat(turn.retrieved()).as("引用的来源都在本轮的检索结果中").containsAll(cited);
        }

        String restricted = createSession("[]");
        Turn denied = ask(restricted, "年终奖一般发几个月？");
        System.out.printf("无权限的问：年终奖一般发几个月？%n答：%s%n检索到：%s%n", denied.answer(), denied.retrieved());
        assertThat(denied.answer()).isNotBlank();
        assertThat(denied.retrieved()).doesNotContain("compensation.md");
        assertThat(denied.events())
                .as("事件与工具结果中没有薪酬制度的切片")
                .doesNotContain("年终奖基数")
                .doesNotContain("调薪幅度")
                .doesNotContain("compensation.md");
    }

    /** 一轮问答：最后的回答、本轮检索结果中的文件、全部事件的原文。 */
    private record Turn(String answer, Set<String> retrieved, String events) {

        /** 回答最后一行"来源："之后列出的语料文件名。 */
        Set<String> cited() {
            Set<String> files = new LinkedHashSet<>();
            int index = answer.lastIndexOf("来源");
            if (index >= 0) {
                for (String file : CORPUS_FILES) {
                    if (answer.substring(index).contains(file)) {
                        files.add(file);
                    }
                }
            }
            return files;
        }
    }

    private Turn ask(String sessionId, String question) {
        ApiTestClient api = new ApiTestClient(RUNTIME_PORT, ApiTestKeys.KEY_A);
        JsonNode existing = api.listEvents(sessionId, "limit=100").body().get("data");
        int before = existing.size();
        long idleBefore = 0;
        for (JsonNode event : existing) {
            if (event.get("type").asString().equals("session.status_idle")) {
                idleBefore++;
            }
        }
        long idleCount = idleBefore;
        Connection stream =
                new SseTestClient(RUNTIME_PORT, ApiTestKeys.KEY_A).open("/v1/sessions/" + sessionId + "/stream", null);
        try {
            assertThat(api.sendMessage(sessionId, question).status()).isEqualTo(202);
            // 流从头回放：等 status_idle 比发消息之前多一个
            stream.await(
                    frames -> frames.stream()
                                    .filter(frame -> "session.status_idle".equals(frame.event()))
                                    .count()
                            > idleCount,
                    WAIT);
        } finally {
            stream.close();
        }
        JsonNode all = api.listEvents(sessionId, "limit=100").body().get("data");
        List<JsonNode> turn = new ArrayList<>();
        for (int i = before; i < all.size(); i++) {
            turn.add(all.get(i));
        }
        Set<String> retrieved = new LinkedHashSet<>();
        String answer = "";
        for (JsonNode event : turn) {
            String type = event.get("type").asString();
            if (type.equals("agent.tool_result")
                    && event.get("data").get("name").asString().equals("knowledge_search")) {
                Matcher matcher = DOCUMENT.matcher(text(event.get("data").get("content")));
                while (matcher.find()) {
                    retrieved.add(matcher.group(1));
                }
            } else if (type.equals("agent.message")) {
                answer = text(event.get("data").get("content"));
            }
        }
        return new Turn(answer, retrieved, turn.toString());
    }

    private static String text(JsonNode blocks) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : blocks) {
            if (block.has("text")) {
                text.append(block.get("text").asString());
            }
        }
        return text.toString();
    }

    private String createSession(String aclTags) {
        ApiResponse created = new ApiTestClient(RUNTIME_PORT, ApiTestKeys.KEY_A)
                .createSession(
                        "{\"agent\": \"" + AGENT + "\", \"environment\": \"" + ENVIRONMENT
                                + "\", \"end_user_attributes\": {\"acl_tags\": " + aclTags + "}}",
                        null);
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        return created.text("id");
    }

    /** 经 knowledge 的公开 API 上传一份语料并等写入完成（写入时 knowledge 调本进程 runtime 的 Embedding）。 */
    private static void ingest(String file, String aclTags) throws IOException {
        String boundary = "----meridian" + UUID.randomUUID();
        StringBuilder head = new StringBuilder()
                .append("--")
                .append(boundary)
                .append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(file)
                .append("\"\r\n")
                .append("Content-Type: text/markdown\r\n\r\n");
        StringBuilder tail = new StringBuilder("\r\n");
        if (aclTags != null) {
            tail.append("--")
                    .append(boundary)
                    .append("\r\n")
                    .append("Content-Disposition: form-data; name=\"acl_tags\"\r\n\r\n")
                    .append(aclTags)
                    .append("\r\n");
        }
        tail.append("--").append(boundary).append("--\r\n");
        byte[] content = Files.readAllBytes(CORPUS.resolve(file));
        byte[] body = concat(
                head.toString().getBytes(StandardCharsets.UTF_8),
                content,
                tail.toString().getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response =
                send(HttpRequest.newBuilder(knowledgeUri("/v1/knowledge-bases/" + knowledgeBase + "/documents"))
                        .header("Authorization", "Bearer " + API_KEY)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        String document =
                JsonMappers.lenient().readTree(response.body()).get("id").asString();
        Instant deadline = Instant.now().plus(WAIT);
        while (true) {
            JsonNode status =
                    knowledgeApi("GET", "/v1/knowledge-bases/" + knowledgeBase + "/documents/" + document, null);
            String state = status.get("status").asString();
            if (state.equals("ready")) {
                return;
            }
            assertThat(state).as(status.toString()).isNotEqualTo("failed");
            assertThat(Instant.now()).as("写入超时：" + status).isBefore(deadline);
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private static JsonNode knowledgeApi(String method, String path, String json) {
        HttpRequest.Builder request = HttpRequest.newBuilder(knowledgeUri(path))
                .header("Authorization", "Bearer " + API_KEY)
                .method(
                        method,
                        json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        HttpResponse<String> response = send(request.build());
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        return JsonMappers.lenient().readTree(response.body());
    }

    private static URI knowledgeUri(String path) {
        return URI.create("http://127.0.0.1:" + knowledge.getMappedPort(18083) + path);
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** control-plane 的 API Key 校验替身：只认本类的 Key，属于 runtime 测试 Key 的租户与项目（会话与知识库同一个项目）。 */
    private static void verifyApiKey(HttpExchange exchange) throws IOException {
        JsonNode request =
                JsonMappers.lenient().readTree(exchange.getRequestBody().readAllBytes());
        boolean known =
                ApiKeyHashes.sha256(API_KEY).equals(request.path("key_hash").asString());
        String body = known
                ? """
                {"api_key_id":"key_live_kb","tenant_id":"%s","project_id":"%s",
                 "scopes":["knowledge:read","knowledge:write"],"rate_limit_per_minute":null,"expires_at":null}
                """.formatted(ApiTestKeys.TENANT, ApiTestKeys.PROJECT_A)
                : "{\"code\":\"api_key.not_found\",\"status\":404}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", known ? "application/json" : "application/problem+json");
        exchange.sendResponseHeaders(known ? 200 : 404, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static boolean imageExists() {
        try {
            Process process = new ProcessBuilder("docker", "image", "inspect", IMAGE)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] all = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, all, offset, part.length);
            offset += part.length;
        }
        return all;
    }

    /** runtime 的出站服务 JWT（调 knowledge 的内部检索）：由测试的令牌签发方签发。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Credentials {

        @Bean
        ServiceCredentialProvider serviceCredentialProvider() {
            return audience -> ApiTestKeys.SERVICE_TOKENS.serviceToken(ServiceJwts.RUNTIME, audience);
        }
    }

    /** DashScope 的三个模型（凭据只写环境变量名）、调用 knowledge_search 的智能体与环境。 */
    private static Path writeConfiguration(String base) throws IOException {
        Path root = Files.createTempDirectory("knowledge-qa-live-it");
        Path agents = Files.createDirectories(root.resolve("agents"));
        Path providers = Files.createDirectories(root.resolve("providers"));
        Files.createDirectories(root.resolve("budgets"));
        String chat = System.getenv().getOrDefault("MERIDIAN_LIVE_CHAT_MODEL", "qwen-plus");
        String embedding = System.getenv().getOrDefault("MERIDIAN_LIVE_EMBEDDING_MODEL", "qwen3.7-text-embedding");
        Files.writeString(providers.resolve("dashscope.yaml"), """
                kind: provider_account
                provider_account_id: prv_live_dashscope
                provider: openai_compatible
                base_url: https://dashscope.aliyuncs.com/compatible-mode/v1
                secret_ref: DASHSCOPE_API_KEY
                models:
                  - model_id: mdl_live_chat
                    upstream_model: %s
                    context_window: 131072
                    max_output_tokens: 512
                  - model_id: mdl_live_embedding
                    kind: embedding
                    upstream_model: %s
                    context_window: 8192
                    max_output_tokens: 1
                    max_batch_size: 20
                """.formatted(chat, embedding));
        Files.writeString(agents.resolve("agent.yaml"), """
                kind: agent_version
                agent_id: %s
                agent_version_id: agv_live_kb_qa_1
                name: policy-qa
                model:
                  model_id: mdl_live_chat
                  max_output_tokens: 512
                system_prompt: %s
                tools: [knowledge_search]
                mcp_servers: []
                skills: []
                subagents: []
                permissions:
                  mode: default
                  rules:
                    - {tool_name: knowledge_search, behavior: allow, layer: agent_version}
                compaction:
                  reserved_tokens: 20000
                plan_mode: false
                knowledge:
                  knowledge_base_ids: [%s]
                  prefetch: false
                  prefetch_top_k: 5
                max_iters: 6
                expose_thinking: false
                """.formatted(
                        AGENT, JsonMappers.lenient().writeValueAsString(SYSTEM_PROMPT), base));
        Files.writeString(agents.resolve("environment.yaml"), """
                kind: environment
                environment_id: %s
                revision: 1
                tier: none
                egress_allowlist: []
                """.formatted(ENVIRONMENT));
        return root;
    }
}
