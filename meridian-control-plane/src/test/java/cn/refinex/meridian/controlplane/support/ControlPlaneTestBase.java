package cn.refinex.meridian.controlplane.support;

import cn.refinex.meridian.testsupport.SpringContainerProperties;
import cn.refinex.meridian.testsupport.auth.KeycloakRealm;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * control-plane 集成测试的基类：随机端口启动完整应用，PostgreSQL、Redis 与 Keycloak 来自 Testcontainers（同一 JVM 共用）。
 *
 * <p>身份（T14b，ADR-0044）：控制台用户是 Keycloak 开发 realm 中动态创建的用户，令牌经口令直接换取；{@code application-test.yml}
 * 中用户标识取 {@code preferred_username}，平台管理员按组 {@value KeycloakRealm#PLATFORM_ADMIN_GROUP} 判定——这是 ADR-0043 要求的第二种
 * 声明配置，写在配置文件里，子类可以用 {@code @DynamicPropertySource} 覆盖（默认的 {@code sub} 配置见 {@code OidcSubjectClaimIT}）。内部接口用本应用的令牌端点签发的服务 JWT（客户端 {@value #RUNTIME_CLIENT}）。
 * 数据跨测试类保留，用例用新建的租户隔离。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class ControlPlaneTestBase {

    /** 平台管理员（经组）。 */
    public static final String PLATFORM = "platform";

    /** 测试中的全部控制台用户（Keycloak 用户名，也是主体标识）。 */
    public static final List<String> USERS =
            List.of(PLATFORM, "owner", "admin", "developer", "viewer", "approver", "outsider", "owner2");

    /** runtime 的服务客户端。 */
    public static final String RUNTIME_CLIENT = "runtime";

    /** runtime 的客户端密钥，只用于测试。 */
    public static final String RUNTIME_SECRET = "test-runtime-client-secret";

    /** 出网代理的服务客户端：只能换取 runtime 的令牌。 */
    public static final String EGRESS_CLIENT = "egress-proxy";

    /** 出网代理的客户端密钥，只用于测试。 */
    public static final String EGRESS_SECRET = "test-egress-client-secret";

    /** 签名密钥目录：同一 JVM 内的全部 Spring 上下文共用，令牌在上下文之间仍然有效。 */
    public static final Path KEY_DIRECTORY = tempDirectory();

    /** Keycloak 的令牌有效 15 分钟，提前换新。 */
    private static final Duration TOKEN_REUSE = Duration.ofMinutes(10);

    private static final Map<String, Cached> TOKENS = new ConcurrentHashMap<>();

    @LocalServerPort
    protected int port;

    @DynamicPropertySource
    static void controlPlaneProperties(DynamicPropertyRegistry registry) {
        SpringContainerProperties.postgres(registry);
        SpringContainerProperties.redis(registry);
        registry.add("meridian.security.oidc.issuer", KeycloakRealm::issuer);
        registry.add("meridian.control-plane.service-tokens.key-directory", KEY_DIRECTORY::toString);
        registry.add("meridian.control-plane.service-tokens.clients[0].service", () -> RUNTIME_CLIENT);
        registry.add("meridian.control-plane.service-tokens.clients[0].secret", () -> RUNTIME_SECRET);
        registry.add("meridian.control-plane.service-tokens.clients[0].audiences[0]", () -> "control-plane");
        registry.add("meridian.control-plane.service-tokens.clients[1].service", () -> EGRESS_CLIENT);
        registry.add("meridian.control-plane.service-tokens.clients[1].secret", () -> EGRESS_SECRET);
        registry.add("meridian.control-plane.service-tokens.clients[1].audiences[0]", () -> "runtime");
    }

    /**
     * 控制台用户的 OIDC access token：第一次时在 Keycloak 中创建用户（{@value #PLATFORM} 加入平台管理员组），缓存 10 分钟。
     *
     * @param subject 用户名
     * @return access token
     */
    public static String token(String subject) {
        Cached cached = TOKENS.get(subject);
        if (cached != null && Instant.now().isBefore(cached.fetchedAt().plus(TOKEN_REUSE))) {
            return cached.token();
        }
        if (PLATFORM.equals(subject)) {
            KeycloakRealm.user(subject, KeycloakRealm.PLATFORM_ADMIN_GROUP);
        } else {
            KeycloakRealm.user(subject);
        }
        String token = KeycloakRealm.token(subject);
        TOKENS.put(subject, new Cached(token, Instant.now()));
        return token;
    }

    /**
     * runtime 调用本应用内部接口的服务 JWT：经令牌端点换取。
     *
     * @return 服务 JWT
     */
    protected String serviceToken() {
        return client().serviceToken(RUNTIME_CLIENT, RUNTIME_SECRET, "control-plane")
                .text("access_token");
    }

    /**
     * 本测试的 HTTP 客户端。
     *
     * @return 客户端
     */
    protected AdminClient client() {
        return new AdminClient("http://127.0.0.1:" + port);
    }

    private static Path tempDirectory() {
        try {
            return Files.createTempDirectory("meridian-service-token-keys");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 一个缓存的令牌。
     *
     * @param token 令牌
     * @param fetchedAt 取得时间
     */
    private record Cached(String token, Instant fetchedAt) {}
}
