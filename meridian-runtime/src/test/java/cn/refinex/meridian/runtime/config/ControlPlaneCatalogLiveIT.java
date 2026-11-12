package cn.refinex.meridian.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.common.net.EndpointPolicy;
import cn.refinex.meridian.runtime.RuntimeApplication;
import cn.refinex.meridian.runtime.config.StubControlPlane.CatalogStub;
import cn.refinex.meridian.runtime.support.ApiTestClient;
import cn.refinex.meridian.runtime.support.ApiTestClient.ApiResponse;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.testsupport.SpringContainerProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * 真实模型测试（T15a-A03 的 {@code -Plive} 部分，ADR-0045）：runtime 以 {@code catalog-source=control-plane} 运行，DashScope 的供应商账号、
 * 模型与凭据都来自 control-plane（{@link StubControlPlane} 按内部接口规范返回，凭据值是环境变量 {@code DASHSCOPE_API_KEY}，只经
 * {@code secrets:resolve} 交给 runtime），一次 {@code /v1/chat/completions}。没有密钥时跳过；1 次调用，最大输出 64 token。
 */
@Tag("live")
@SpringBootTest(
        classes = RuntimeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "meridian.runtime.worker.enabled=false")
@ActiveProfiles("test")
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class ControlPlaneCatalogLiveIT {

    private static final String DASHSCOPE = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String CHAT_MODEL =
            Objects.requireNonNullElse(System.getenv("MERIDIAN_LIVE_CHAT_MODEL"), "qwen-plus");
    private static final StubControlPlane CONTROL_PLANE = StubControlPlane.start();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SpringContainerProperties.postgres(registry);
        SpringContainerProperties.redis(registry);
        SpringContainerProperties.minio(registry);
        registry.add("meridian.security.service-client.control-plane-url", CONTROL_PLANE::baseUrl);
        registry.add("meridian.security.service-jwt.client-secret", () -> StubControlPlane.RUNTIME_SECRET);
        registry.add("meridian.runtime.model-routing.catalog-source", () -> "control-plane");
        registry.add("meridian.runtime.model-routing.providers-directory", () -> "target/no-such-providers");
        // fake-ip 透明代理把公网域名解析到 198.18.0.0/15（本机如此）：显式放行，其余与默认策略相同
        List<String> networks = new ArrayList<>(EndpointPolicy.DEFAULT_PRIVATE_NETWORKS);
        networks.add("198.18.0.0/15");
        for (int i = 0; i < networks.size(); i++) {
            String network = networks.get(i);
            registry.add("meridian.runtime.model-routing.private-networks[" + i + "]", () -> network);
        }
    }

    @LocalServerPort
    private int port;

    @BeforeAll
    static void requireApiKey() {
        String key = System.getenv("DASHSCOPE_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "没有设置 DASHSCOPE_API_KEY，跳过真实模型测试");
    }

    @AfterAll
    static void close() {
        CONTROL_PLANE.close();
    }

    @Test
    void dashScopeIsCalledWithTheProviderAndCredentialFromControlPlane(CapturedOutput output) {
        String tenantId = Ids.newId(IdPrefix.TENANT);
        String secretId = Ids.newId(IdPrefix.SECRET);
        String modelId = Ids.newId(IdPrefix.MODEL);
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        CONTROL_PLANE.secret(secretId, tenantId, null, apiKey);
        CONTROL_PLANE.model(new CatalogStub(
                modelId,
                tenantId,
                Ids.newId(IdPrefix.PROVIDER_ACCOUNT),
                DASHSCOPE,
                secretId,
                "prb_live",
                1,
                CHAT_MODEL));
        ApiTestClient api = new ApiTestClient(port, CONTROL_PLANE.issue(tenantId, 600, "models:invoke"));

        ApiResponse response = api.call(
                "POST /v1/chat/completions",
                HttpMethod.POST,
                "/v1/chat/completions",
                "{\"model\": \"" + modelId + "\", \"max_tokens\": 64,"
                        + " \"messages\": [{\"role\": \"user\", \"content\": \"用一句话介绍你自己。\"}]}",
                null);

        assertThat(response.status()).as(String.valueOf(response.body())).isEqualTo(200);
        assertThat(response.body()
                        .get("choices")
                        .get(0)
                        .get("message")
                        .get("content")
                        .asString())
                .isNotBlank();
        assertThat(CONTROL_PLANE.secretResolutions.get()).isEqualTo(1);
        JsonNode usage = JsonMappers.lenient()
                .readTree(DbAssertions.list(
                                "SELECT payload::text FROM rt.outbox WHERE topic = 'usage' AND payload->>'tenant_id' = ?",
                                String.class,
                                tenantId)
                        .getFirst());
        assertThat(usage.get("upstream_model").asString()).isEqualTo(CHAT_MODEL);
        assertThat(usage.get("usage_source").asString()).isEqualTo("provider");
        assertThat(usage.get("input_tokens").asInt()).isPositive();
        assertThat(usage.get("price_book_version").asInt()).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain(apiKey);
        System.out.println("T15a-A03 live: model=" + CHAT_MODEL + " input_tokens="
                + usage.get("input_tokens").asInt()
                + " output_tokens=" + usage.get("output_tokens").asInt() + " price_book_version="
                + usage.get("price_book_version").asInt());
    }
}
