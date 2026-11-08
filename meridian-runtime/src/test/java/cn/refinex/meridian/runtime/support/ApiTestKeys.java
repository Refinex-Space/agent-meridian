package cn.refinex.meridian.runtime.support;

import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
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

    /** 内部接口的开发期服务凭据（服务名 {@code knowledge}）。 */
    public static final String SERVICE_CREDENTIAL = "svc_test_knowledge";

    private ApiTestKeys() {}

    /**
     * 注册三个 Key、内部接口的服务凭据与开发配置目录。项目 A 的读写 Key 另有 {@code models:invoke}。
     *
     * @param registry Spring 的动态属性注册表
     */
    public static void register(DynamicPropertyRegistry registry) {
        String prefix = "meridian.security.dev-api-keys.";
        registry.add(prefix + "enabled", () -> "true");
        key(registry, 0, KEY_A, "key_test_a", PROJECT_A, "sessions:read", "sessions:write", "models:invoke");
        key(registry, 1, KEY_B, "key_test_b", PROJECT_B, "sessions:read", "sessions:write");
        key(registry, 2, KEY_A_READ_ONLY, "key_test_a_read", PROJECT_A, "sessions:read");
        registry.add("meridian.security.dev-service-credentials.enabled", () -> "true");
        registry.add("meridian.security.dev-service-credentials.credentials[0].credential", () -> SERVICE_CREDENTIAL);
        registry.add("meridian.security.dev-service-credentials.credentials[0].service", () -> "knowledge");
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
