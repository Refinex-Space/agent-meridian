package cn.refinex.meridian.runtime.workspace.api;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.engine.spi.port.WorkspaceFiles;
import cn.refinex.meridian.persistence.objectstore.ObjectStore;
import cn.refinex.meridian.runtime.sandbox.domain.SandboxObjectKeys;
import cn.refinex.meridian.runtime.sandbox.infra.SandboxOutputRepository;
import cn.refinex.meridian.runtime.support.ApiTestBase;
import cn.refinex.meridian.runtime.support.ApiTestClient;
import cn.refinex.meridian.runtime.support.ApiTestClient.ApiResponse;
import cn.refinex.meridian.runtime.support.ApiTestClient.BinaryResponse;
import cn.refinex.meridian.runtime.support.ApiTestKeys;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.RuntimeTestDatabase;
import cn.refinex.meridian.runtime.workspace.app.DatabaseWorkspaceStore;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * T17b-A02：工作区 API 在两个档位下的行为（ADR-0050）。{@code none} 档读写智能体使用的同一份平台存储，受保护路径只读；{@code sandbox}
 * 档只读 {@code outputs/} 的副本；路径穿越与不规整的路径一律拒绝。每次交互都按规范校验。大小上限调成 64 KB 以便测试 413。
 */
class WorkspaceApiIT extends ApiTestBase {

    private static final String SANDBOX_ENVIRONMENT = "env_dev_sandbox";

    @Autowired
    DatabaseWorkspaceStore store;

    @Autowired
    SandboxOutputRepository outputs;

    @Autowired
    ObjectStore objects;

    @DynamicPropertySource
    static void smallLimit(DynamicPropertyRegistry registry) {
        registry.add("meridian.runtime.files.max-size", () -> "64KB");
    }

    @Test
    void theNoneTierReadsAndWritesThePlatformWorkspace() {
        String sessionId = createSession("u_workspace");
        ApiTestClient api = api(ApiTestKeys.KEY_A);

        BinaryResponse written = put(api, sessionId, "reports%2Fq3.md", "# Q3\n收入增长 12%");
        assertThat(written.status()).isEqualTo(200);
        assertThat(json(written).get("path").asString()).isEqualTo("reports/q3.md");
        assertThat(json(written).get("size_bytes").asLong())
                .isEqualTo("# Q3\n收入增长 12%".getBytes(StandardCharsets.UTF_8).length);
        assertThat(put(api, sessionId, "reports%2Fq3.md", "# Q3 v2").status())
                .as("覆盖")
                .isEqualTo(200);

        // 与智能体同一份存储：智能体（RemoteFilesystem）写的文件经 API 读得到，API 写的文件在会话命名空间中
        List<String> namespace =
                WorkspaceFiles.namespace(ApiTestKeys.TENANT, ApiTestKeys.PROJECT_A, "u_workspace", sessionId);
        store.put(
                namespace,
                "/agent/notes.txt",
                WorkspaceFiles.encode("智能体写的".getBytes(StandardCharsets.UTF_8), Instant.now()));
        BinaryResponse agentFile = get(api, sessionId, "agent%2Fnotes.txt");
        assertThat(new String(agentFile.bytes(), StandardCharsets.UTF_8)).isEqualTo("智能体写的");
        assertThat(agentFile.headers().getContentType().toString()).startsWith("text/plain");
        assertThat(agentFile.headers().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(new String(get(api, sessionId, "reports%2Fq3.md").bytes(), StandardCharsets.UTF_8))
                .isEqualTo("# Q3 v2");
        assertThat(DbAssertions.list(
                        "SELECT key FROM rt.workspace_entries WHERE namespace = ?::text[] ORDER BY key",
                        String.class,
                        "{" + String.join(",", namespace) + "}"))
                .containsExactly("/agent/notes.txt", "/reports/q3.md");

        // 列表：按路径排序、游标分页、前缀过滤
        ApiResponse first = list(api, sessionId, "limit=1");
        assertThat(paths(first)).containsExactly("agent/notes.txt");
        assertThat(first.body().get("has_more").asBoolean()).isTrue();
        ApiResponse second = list(api, sessionId, "limit=1&after=" + first.text("next_cursor"));
        assertThat(paths(second)).containsExactly("reports/q3.md");
        assertThat(second.body().get("has_more").asBoolean()).isFalse();
        assertThat(paths(list(api, sessionId, "prefix=reports/"))).containsExactly("reports/q3.md");

        assertThat(get(api, sessionId, "missing.txt").problem().get("code").asString())
                .isEqualTo("workspace.file_not_found");
    }

    @Test
    void protectedPathsTraversalAndOversizedContentAreRejected() {
        String sessionId = createSession(null);
        ApiTestClient api = api(ApiTestKeys.KEY_A);

        for (String path : List.of(
                "AGENTS.md", "skills%2Fdocx%2Fhelper.py", "subagents%2Fresearcher.md", "tools.json", "MEMORY.md")) {
            BinaryResponse rejected = put(api, sessionId, path, "改写提示词");
            assertThat(rejected.status()).as(path).isEqualTo(403);
            assertThat(rejected.problem().get("code").asString()).isEqualTo("workspace.protected_path");
        }
        for (String path : List.of(
                "..%2Fetc%2Fpasswd", "a%2F..%2F..%2Fb", "%2Fabs.txt", "a%2F.%2Fb", "a%2F%2Fb", "a%5Cb", "dir%2F")) {
            BinaryResponse write = put(api, sessionId, path, "x");
            assertThat(write.status()).as("写入 " + path).isEqualTo(400);
            assertThat(write.problem().get("code").asString()).isEqualTo("workspace.invalid_path");
            assertThat(get(api, sessionId, path).problem().get("code").asString())
                    .as("读取 " + path)
                    .isEqualTo("workspace.invalid_path");
        }
        assertThat(DbAssertions.list(
                        "SELECT count(*) FROM rt.workspace_entries WHERE namespace[4] = ?", Long.class, sessionId))
                .as("没有写入任何条目")
                .containsExactly(0L);

        BinaryResponse tooLarge = api.binary(
                "PUT /v1/sessions/{session_id}/workspace/files/{path}",
                HttpMethod.PUT,
                "/v1/sessions/" + sessionId + "/workspace/files/big.bin",
                new byte[64 * 1024 + 1]);
        assertThat(tooLarge.status()).isEqualTo(413);
        assertThat(tooLarge.problem().get("code").asString()).isEqualTo("file.too_large");

        assertThat(api(ApiTestKeys.KEY_A_READ_ONLY)
                        .binary(
                                "PUT /v1/sessions/{session_id}/workspace/files/{path}",
                                HttpMethod.PUT,
                                "/v1/sessions/" + sessionId + "/workspace/files/a.txt",
                                new byte[] {1})
                        .status())
                .isEqualTo(403);
        assertThat(get(api(ApiTestKeys.KEY_B), sessionId, "a.txt")
                        .problem()
                        .get("code")
                        .asString())
                .as("其他项目的会话与不存在相同")
                .isEqualTo("session.not_found");

        assertThat(api.archiveSession(sessionId, null).status()).isEqualTo(200);
        BinaryResponse archived = put(api, sessionId, "after.txt", "x");
        assertThat(archived.status()).isEqualTo(409);
        assertThat(archived.problem().get("code").asString()).isEqualTo("session.terminated");
    }

    @Test
    void theSandboxTierOnlyReadsTheOutputsCopy() {
        ApiTestClient api = api(ApiTestKeys.KEY_A);
        ApiResponse created = api.createSession(
                "{\"agent\": \"" + AGENT + "\", \"environment\": \"" + SANDBOX_ENVIRONMENT + "\"}", null);
        assertThat(created.status()).isEqualTo(201);
        String sessionId = created.text("id");
        byte[] report = "季度报告".getBytes(StandardCharsets.UTF_8);
        String key = SandboxObjectKeys.output(ApiTestKeys.TENANT, ApiTestKeys.PROJECT_A, sessionId, "reports/q3.md");
        objects.put(key, report, "application/octet-stream");
        PlatformContexts.run(
                projectA(),
                () -> RuntimeTestDatabase.transactions()
                        .executeWithoutResult(status ->
                                outputs.upsert(sessionId, "reports/q3.md", report.length, "sha", key, Instant.now())));

        BinaryResponse output = get(api, sessionId, "outputs%2Freports%2Fq3.md");
        assertThat(output.status()).isEqualTo(200);
        assertThat(output.bytes()).isEqualTo(report);
        assertThat(get(api, sessionId, "reports%2Fq3.md").problem().get("code").asString())
                .as("outputs/ 之外的路径不可读")
                .isEqualTo("workspace.file_not_found");
        assertThat(paths(list(api, sessionId, null))).containsExactly("outputs/reports/q3.md");
        assertThat(paths(list(api, sessionId, "prefix=outputs/reports/"))).containsExactly("outputs/reports/q3.md");
        assertThat(paths(list(api, sessionId, "prefix=notes/"))).isEmpty();

        BinaryResponse write = put(api, sessionId, "outputs%2Fnew.md", "x");
        assertThat(write.status()).isEqualTo(409);
        assertThat(write.problem().get("code").asString()).isEqualTo("workspace.read_only");
    }

    private static BinaryResponse put(ApiTestClient api, String sessionId, String encodedPath, String content) {
        return api.binary(
                "PUT /v1/sessions/{session_id}/workspace/files/{path}",
                HttpMethod.PUT,
                "/v1/sessions/" + sessionId + "/workspace/files/" + encodedPath,
                content.getBytes(StandardCharsets.UTF_8));
    }

    private static BinaryResponse get(ApiTestClient api, String sessionId, String encodedPath) {
        return api.binary(
                "GET /v1/sessions/{session_id}/workspace/files/{path}",
                HttpMethod.GET,
                "/v1/sessions/" + sessionId + "/workspace/files/" + encodedPath,
                null);
    }

    private static ApiResponse list(ApiTestClient api, String sessionId, String query) {
        return api.call(
                "GET /v1/sessions/{session_id}/workspace:list",
                HttpMethod.GET,
                "/v1/sessions/" + sessionId + "/workspace:list" + (query == null ? "" : "?" + query),
                null,
                null);
    }

    private static List<String> paths(ApiResponse page) {
        return page.body()
                .get("data")
                .valueStream()
                .map(entry -> entry.get("path").asString())
                .toList();
    }

    private static JsonNode json(BinaryResponse response) {
        return cn.refinex.meridian.common.json.JsonMappers.lenient()
                .readTree(new String(response.bytes(), StandardCharsets.UTF_8));
    }
}
