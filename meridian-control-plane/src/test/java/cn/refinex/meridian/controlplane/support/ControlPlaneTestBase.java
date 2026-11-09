package cn.refinex.meridian.controlplane.support;

import cn.refinex.meridian.testsupport.SpringContainerProperties;
import java.util.List;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * control-plane 集成测试的基类：随机端口启动完整应用，PostgreSQL 与 Redis 来自 Testcontainers（同一 JVM 共用），控制台用户与服务凭据
 * 用开发期配置注入（T14a 的"测试注入的身份"，T14b 换成 OIDC）。
 *
 * <p>用户：{@link #USERS} 中的每个主体，令牌为 {@code tok-<主体>}；{@value #PLATFORM} 是平台管理员。数据跨测试类保留，用例用新建的
 * 租户隔离。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class ControlPlaneTestBase {

    /** 平台管理员。 */
    public static final String PLATFORM = "platform";

    /** 测试中的全部控制台用户（主体标识）。 */
    public static final List<String> USERS =
            List.of(PLATFORM, "owner", "admin", "developer", "viewer", "approver", "outsider", "owner2");

    /** runtime 调用内部接口用的服务凭据。 */
    public static final String SERVICE_CREDENTIAL = "svc_test_runtime";

    @LocalServerPort
    protected int port;

    @DynamicPropertySource
    static void controlPlaneProperties(DynamicPropertyRegistry registry) {
        SpringContainerProperties.postgres(registry);
        SpringContainerProperties.redis(registry);
        registry.add("meridian.security.dev-console-users.enabled", () -> "true");
        for (int i = 0; i < USERS.size(); i++) {
            String subject = USERS.get(i);
            registry.add("meridian.security.dev-console-users.users[" + i + "].token", () -> token(subject));
            registry.add("meridian.security.dev-console-users.users[" + i + "].subject", () -> subject);
        }
        registry.add("meridian.security.dev-service-credentials.enabled", () -> "true");
        registry.add("meridian.security.dev-service-credentials.credentials[0].credential", () -> SERVICE_CREDENTIAL);
        registry.add("meridian.security.dev-service-credentials.credentials[0].service", () -> "runtime");
        registry.add("meridian.control-plane.platform-admins[0]", () -> PLATFORM);
    }

    /**
     * 控制台用户的令牌。
     *
     * @param subject 主体标识
     * @return 令牌
     */
    public static String token(String subject) {
        return "tok-" + subject;
    }

    /**
     * 本测试的 HTTP 客户端。
     *
     * @return 客户端
     */
    protected AdminClient client() {
        return new AdminClient("http://127.0.0.1:" + port);
    }
}
