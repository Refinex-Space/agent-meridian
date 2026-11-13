package cn.refinex.meridian.testsupport.auth;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 集成测试共用的 Keycloak（ADR-0043、ADR-0044）：Testcontainers 的 Keycloak 模块启动容器，导入仓库中的开发 realm
 * {@code deploy/keycloak/meridian-dev-realm.json}（与 Compose 的 {@code auth} profile 相同），同一个测试 JVM 内共用。
 *
 * <p>测试用户经管理 REST API 动态创建（{@link #user}），令牌经开发脚本用的客户端 {@value #CLI_CLIENT} 以口令直接换取，形状与控制台
 * 客户端相同（{@code aud} 含 {@value #AUDIENCE}，{@code groups} 是组名）。同名用户只创建一次，跨测试类复用。
 */
public final class KeycloakRealm {

    /** Keycloak 镜像，与 {@code deploy/compose/compose.yaml} 的 {@code auth} profile 一致，修改时两处一起改。 */
    public static final String IMAGE = "quay.io/keycloak/keycloak:26.4.7";

    /** realm 名。 */
    public static final String REALM = "meridian-dev";

    /** 令牌的受众（realm 的受众映射器）。 */
    public static final String AUDIENCE = "meridian";

    /** 平台管理员组（按组判定平台管理员，ADR-0043）。 */
    public static final String PLATFORM_ADMIN_GROUP = "meridian-platform-admins";

    /** 以口令直接换取令牌的客户端，只在开发 realm 中存在。 */
    public static final String CLI_CLIENT = "meridian-dev-cli";

    /** 动态创建的测试用户的口令，只用于测试容器。 */
    public static final String PASSWORD = "test-password";

    static final String REALM_FILE = "deploy/keycloak/meridian-dev-realm.json";

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Map<String, String> USER_IDS = new ConcurrentHashMap<>();

    private KeycloakRealm() {}

    /**
     * 签发方（{@code iss}），即被测服务的 {@code meridian.security.oidc.issuer}。
     *
     * @return 例如 {@code http://localhost:32768/realms/meridian-dev}
     */
    public static String issuer() {
        return Holder.INSTANCE.getAuthServerUrl() + "/realms/" + REALM;
    }

    /**
     * 创建测试用户（已存在时直接返回），可选加入组。
     *
     * @param username 用户名，同时是 {@code preferred_username}
     * @param groups 组名
     * @return 用户 ID，即令牌的 {@code sub}
     */
    public static String user(String username, String... groups) {
        return USER_IDS.computeIfAbsent(
                username, name -> create(name, name + "@test.meridian.local", true, List.of(groups)));
    }

    /**
     * 创建带指定邮箱的测试用户（已存在时直接返回）：按邮箱邀请与首次登录绑定的测试用（T19b，ADR-0053）。
     *
     * @param username 用户名，同时是 {@code preferred_username}
     * @param email 邮箱
     * @param emailVerified 邮箱是否已验证（令牌的 {@code email_verified}）
     * @return 用户 ID，即令牌的 {@code sub}
     */
    public static String userWithEmail(String username, String email, boolean emailVerified) {
        return USER_IDS.computeIfAbsent(username, name -> create(name, email, emailVerified, List.of()));
    }

    /**
     * 以口令换取用户的 access token。
     *
     * @param username 用户名（先经 {@link #user} 创建，或开发 realm 中的用户）
     * @return access token
     */
    public static String token(String username) {
        return token(username, USER_IDS.containsKey(username) ? PASSWORD : "meridian-dev");
    }

    /**
     * 以口令换取 access token。
     *
     * @param username 用户名
     * @param password 口令
     * @return access token
     */
    public static String token(String username, String password) {
        JsonNode body = post(
                Holder.INSTANCE.getAuthServerUrl() + "/realms/" + REALM + "/protocol/openid-connect/token",
                null,
                "application/x-www-form-urlencoded",
                form(Map.of(
                        "grant_type", "password",
                        "client_id", CLI_CLIENT,
                        "username", username,
                        "password", password,
                        "scope", "openid")),
                200);
        return body.get("access_token").asString();
    }

    private static String create(String username, String email, boolean emailVerified, List<String> groups) {
        String admin = post(
                        Holder.INSTANCE.getAuthServerUrl() + "/realms/master/protocol/openid-connect/token",
                        null,
                        "application/x-www-form-urlencoded",
                        form(Map.of(
                                "grant_type",
                                "password",
                                "client_id",
                                "admin-cli",
                                "username",
                                Holder.INSTANCE.getAdminUsername(),
                                "password",
                                Holder.INSTANCE.getAdminPassword())),
                        200)
                .get("access_token")
                .asString();
        List<String> paths = new ArrayList<>();
        groups.forEach(group -> paths.add("/" + group));
        Map<String, Object> user = Map.of(
                "username",
                username,
                "enabled",
                true,
                "email",
                email,
                "emailVerified",
                emailVerified,
                "firstName",
                "Test",
                "lastName",
                username,
                "groups",
                paths,
                "credentials",
                List.of(Map.of("type", "password", "value", PASSWORD, "temporary", false)));
        HttpResponse<String> created = send(HttpRequest.newBuilder(
                        URI.create(Holder.INSTANCE.getAuthServerUrl() + "/admin/realms/" + REALM + "/users"))
                .header("Authorization", "Bearer " + admin)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(user)))
                .build());
        if (created.statusCode() != 201) {
            throw new IllegalStateException("创建 Keycloak 用户失败：" + created.statusCode() + " " + created.body());
        }
        String location = created.headers().firstValue("Location").orElseThrow();
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private static JsonNode post(String url, String bearer, String contentType, String body, int expected) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        HttpResponse<String> response = send(request.build());
        if (response.statusCode() != expected) {
            throw new IllegalStateException(
                    "Keycloak 请求失败：" + url + " " + response.statusCode() + " " + response.body());
        }
        return JSON.readTree(response.body());
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String form(Map<String, String> values) {
        StringJoiner joiner = new StringJoiner("&");
        values.forEach((key, value) -> joiner.add(URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return joiner.toString();
    }

    /** 从测试的工作目录（模块目录）向上找仓库中的 realm 文件。 */
    static Path realmFile() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(REALM_FILE);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到 " + REALM_FILE + "：测试要在仓库内运行");
    }

    /** 首次访问时启动，JVM 退出后由 Testcontainers 回收。 */
    private static final class Holder {
        static final KeycloakContainer INSTANCE = start();

        @SuppressWarnings("resource") // 容器与 JVM 同生命周期
        private static KeycloakContainer start() {
            KeycloakContainer container = new KeycloakContainer(IMAGE)
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(realmFile(), 0644),
                            "/opt/keycloak/data/import/meridian-dev-realm.json");
            container.start();
            // master realm 默认对非本机来源要求 HTTPS，宿主经端口映射访问时被当作外部请求：在容器内（本机）用 kcadm 关掉，
            // 测试才能经管理 REST API 创建用户。只影响测试容器
            exec(
                    container,
                    "config",
                    "credentials",
                    "--server",
                    "http://localhost:8080",
                    "--realm",
                    "master",
                    "--user",
                    container.getAdminUsername(),
                    "--password",
                    container.getAdminPassword());
            exec(container, "update", "realms/master", "-s", "sslRequired=NONE");
            return container;
        }

        private static void exec(KeycloakContainer container, String... args) {
            String[] command = new String[args.length + 1];
            command[0] = "/opt/keycloak/bin/kcadm.sh";
            System.arraycopy(args, 0, command, 1, args.length);
            try {
                org.testcontainers.containers.Container.ExecResult result = container.execInContainer(command);
                if (result.getExitCode() != 0) {
                    throw new IllegalStateException("kcadm 失败：" + args[0] + " " + result.getStderr());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
