package cn.refinex.meridian.runtime.support;

import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.testsupport.auth.TestTokenIssuer;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * 集成测试的开发期 API Key（{@code DevApiKeyResolver}，test profile）：同一租户下两个项目，外加一个只读 Key。
 * 明文只在测试中使用。
 */
public final class ApiTestKeys {

    /** 租户。 */
    public static final String TENANT = Ids.newId(IdPrefix.TENANT);

    /** 项目 A。 */
    public static final String PROJECT_A = Ids.newId(IdPrefix.PROJECT);

    /** 项目 B。 */
    public static final String PROJECT_B = Ids.newId(IdPrefix.PROJECT);

    /** 项目 A 的读写 Key。 */
    public static final String KEY_A = "mk_test_project_a";

    /** 项目 B 的读写 Key。 */
    public static final String KEY_B = "mk_test_project_b";

    /** 项目 A 的只读 Key。 */
    public static final String KEY_A_READ_ONLY = "mk_test_project_a_read";

    /** 项目 A 的审批 Key：只能读会话与回应待处理动作（{@code actions:respond}，ADR-0053）。 */
    public static final String KEY_A_APPROVER = "mk_test_project_a_approver";

    /** 测试类自己登记开发期 Key 时从这个下标开始，前面的下标由 {@link #register} 占用（否则属性互相覆盖）。 */
    public static final int FIRST_FREE_KEY_INDEX = 4;

    /** 服务 JWT 的签发方：测试夹具，runtime 经它的 JWKS 校验内部接口的令牌（ADR-0044）。 */
    public static final TestTokenIssuer SERVICE_TOKENS = TestTokenIssuer.start();

    private ApiTestKeys() {}

    /**
     * knowledge 调用 runtime 内部接口（Embedding、rerank）的服务 JWT。
     *
     * @return 令牌
     */
    public static String knowledgeToken() {
        return SERVICE_TOKENS.serviceToken("knowledge", "runtime");
    }

    /**
     * 出网代理调用 runtime 出网授权接口的服务 JWT。
     *
     * @return 令牌
     */
    public static String egressProxyToken() {
        return SERVICE_TOKENS.serviceToken("egress-proxy", "runtime");
    }

    /**
     * 注册四个 Key、内部接口的服务 JWT 公钥与开发配置目录。项目 A 的读写 Key 另有 {@code models:invoke} 与 {@code files:write}。
     *
     * @param registry Spring 的动态属性注册表
     */
    public static void register(DynamicPropertyRegistry registry) {
        String prefix = "meridian.security.dev-api-keys.";
        registry.add(prefix + "enabled", () -> "true");
        key(
                registry,
                0,
                KEY_A,
                "key_test_a",
                PROJECT_A,
                "sessions:read",
                "sessions:write",
                "models:invoke",
                "files:write");
        key(registry, 1, KEY_B, "key_test_b", PROJECT_B, "sessions:read", "sessions:write");
        key(registry, 2, KEY_A_READ_ONLY, "key_test_a_read", PROJECT_A, "sessions:read");
        key(registry, 3, KEY_A_APPROVER, "key_test_a_approver", PROJECT_A, "sessions:read", "actions:respond");
        registry.add("meridian.security.service-jwt.jwks-url", SERVICE_TOKENS::jwksUrl);
        // 测试的工作目录是模块目录，仓库根目录的 dev-config 在上一级
        registry.add("meridian.runtime.dev-agent-config.directory", () -> "../dev-config/agents");
    }

    private static void key(
            DynamicPropertyRegistry registry, int index, String key, String keyId, String project, String... scopes) {
        String prefix = "meridian.security.dev-api-keys.keys[" + index + "].";
        registry.add(prefix + "key", () -> key);
        registry.add(prefix + "api-key-id", () -> keyId);
        registry.add(prefix + "tenant-id", () -> TENANT);
        registry.add(prefix + "project-id", () -> project);
        for (int i = 0; i < scopes.length; i++) {
            String scope = scopes[i];
            registry.add(prefix + "scopes[" + i + "]", () -> scope);
        }
    }
}
