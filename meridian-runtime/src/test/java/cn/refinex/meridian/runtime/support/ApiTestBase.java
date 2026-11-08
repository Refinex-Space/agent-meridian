package cn.refinex.meridian.runtime.support;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.runtime.RuntimeApplication;
import cn.refinex.meridian.runtime.support.ApiTestClient.ApiResponse;
import cn.refinex.meridian.testsupport.SpringContainerProperties;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;

/**
 * 公开 API 集成测试的公共容器：完整的 runtime（{@code meridian.runtime.worker.enabled=false}，不装配内核，轮次只入队不执行），随机端口，三个开发期 API Key
 * （{@link ApiTestKeys}）。子类不加 {@code @DirtiesContext}，配置相同的测试类共用一个容器。
 */
@SpringBootTest(classes = RuntimeApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class ApiTestBase {

    /** 开发配置中的智能体（{@code dev-config/agents/assistant.yaml}）。 */
    protected static final String AGENT = "agt_dev_assistant";

    /** 开发配置中的环境。 */
    protected static final String ENVIRONMENT = "env_dev_none";

    @LocalServerPort
    protected int port;

    @DynamicPropertySource
    static void apiProperties(DynamicPropertyRegistry registry) {
        SpringContainerProperties.postgres(registry);
        SpringContainerProperties.redis(registry);
        SpringContainerProperties.minio(registry);
        ApiTestKeys.register(registry);
        registry.add("meridian.runtime.worker.enabled", () -> "false");
    }

    /**
     * 以某个 API Key 调用 API 的客户端。
     *
     * @param apiKey API Key 明文；可为空，表示不带认证
     * @return 客户端
     */
    protected ApiTestClient api(String apiKey) {
        return new ApiTestClient(port, apiKey);
    }

    /**
     * 以项目 A 的 Key 创建一个会话。
     *
     * @param endUserId 终端用户；可为空
     * @return 会话 ID
     */
    protected String createSession(String endUserId) {
        String endUser = endUserId == null ? "" : ", \"end_user_id\": \"" + endUserId + "\"";
        ApiResponse response = api(ApiTestKeys.KEY_A)
                .createSession(
                        "{\"agent\": \"" + AGENT + "\", \"environment\": \"" + ENVIRONMENT + "\"" + endUser + "}",
                        null);
        if (response.status() != 201) {
            throw new AssertionError("创建会话失败：" + response.body());
        }
        return response.text("id");
    }

    /**
     * 项目 A 的平台上下文，测试直接调用项目级仓储时使用。
     *
     * @return 上下文
     */
    protected static PlatformContext projectA() {
        return PlatformContext.builder()
                .tenantId(ApiTestKeys.TENANT)
                .projectId(ApiTestKeys.PROJECT_A)
                .apiKeyId("key_test_a")
                .build();
    }

    /**
     * 以服务凭据调用内部接口（{@code POST}，JSON 请求体），请求与响应都按 {@code meridian-internal-v1.yaml} 校验。
     *
     * @param path 路径
     * @param body 请求体
     * @param credential 服务凭据；可为空，表示不带认证
     * @return 状态码与响应体
     */
    protected InternalResponse internal(String path, String body, String credential) {
        return internal(path, body, credential, true);
    }

    /**
     * 同 {@link #internal(String, String, String)}，请求故意不合法时只校验响应。
     *
     * @param path 路径
     * @param body 请求体
     * @param credential 服务凭据；可为空，表示不带认证
     * @param validateRequest 是否按规范校验请求
     * @return 状态码与响应体
     */
    protected InternalResponse internal(String path, String body, String credential, boolean validateRequest) {
        WebTestClient.RequestBodySpec spec = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(60))
                .build()
                .post()
                .uri(path);
        if (credential != null) {
            spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + credential);
        }
        EntityExchangeResult<byte[]> result = spec.contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectBody(byte[].class)
                .returnResult();
        String text =
                result.getResponseBody() == null ? "" : new String(result.getResponseBody(), StandardCharsets.UTF_8);
        SimpleResponse.Builder response =
                SimpleResponse.Builder.status(result.getStatus().value()).withBody(text);
        result.getResponseHeaders().forEach(response::withHeader);
        SimpleRequest.Builder request = new SimpleRequest.Builder(Request.Method.POST, path)
                .withContentType(MediaType.APPLICATION_JSON_VALUE)
                .withBody(body);
        if (credential != null) {
            request.withHeader(HttpHeaders.AUTHORIZATION, "Bearer " + credential);
        }
        if (validateRequest) {
            ContractValidator.validateInternal(request.build(), response.build());
        } else {
            ContractValidator.validateInternalResponse(path, Request.Method.POST, response.build());
        }
        return new InternalResponse(
                result.getStatus().value(), JsonMappers.lenient().readTree(text));
    }

    /**
     * 一次内部接口请求的结果。
     *
     * @param status 状态码
     * @param body 响应体
     */
    protected record InternalResponse(int status, JsonNode body) {}
}
