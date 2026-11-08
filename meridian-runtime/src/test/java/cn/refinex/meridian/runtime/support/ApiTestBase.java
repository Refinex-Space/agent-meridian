package cn.refinex.meridian.runtime.support;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.runtime.RuntimeApplication;
import cn.refinex.meridian.runtime.support.ApiTestClient.ApiResponse;
import cn.refinex.meridian.testsupport.SpringContainerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
}
