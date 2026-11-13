package cn.refinex.meridian.runtime.file.api;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.runtime.support.ApiTestBase;
import cn.refinex.meridian.runtime.support.ApiTestClient;
import cn.refinex.meridian.runtime.support.ApiTestClient.ApiResponse;
import cn.refinex.meridian.runtime.support.ApiTestClient.BinaryResponse;
import cn.refinex.meridian.runtime.support.ApiTestKeys;
import cn.refinex.meridian.runtime.support.DbAssertions;
import cn.refinex.meridian.runtime.support.RuntimeTestObjectStore;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 文件的公开 API（ADR-0050）：上传按项目隔离、媒体类型由服务端检测、文件名规范化、大小上限（测试中调成 64 KB）、幂等键；下载带检测的类型与
 * {@code attachment}。每次交互都按规范校验。
 */
class FilesApiIT extends ApiTestBase {

    @DynamicPropertySource
    static void smallLimit(DynamicPropertyRegistry registry) {
        registry.add("meridian.runtime.files.max-size", () -> "64KB");
    }

    @Test
    void uploadedFilesAreDetectedNormalizedAndScopedToTheProject() {
        ApiTestClient api = api(ApiTestKeys.KEY_A);
        String html = "<!DOCTYPE html><html><body><script>alert(1)</script></body></html>";

        ApiResponse uploaded =
                api.uploadFile("C:\\Users\\me\\..\\报表\u202e .png", html.getBytes(StandardCharsets.UTF_8), null);

        assertThat(uploaded.status()).isEqualTo(201);
        String id = uploaded.text("id");
        assertThat(id).startsWith("fil_");
        assertThat(uploaded.text("filename")).as("去掉目录与双向文本覆盖符").isEqualTo("报表 .png");
        assertThat(uploaded.text("media_type")).as("按内容检测，扩展名与表单的类型都不采用").isEqualTo("text/html");
        assertThat(uploaded.body().get("size_bytes").asLong()).isEqualTo(html.getBytes(StandardCharsets.UTF_8).length);
        Map<String, Object> row =
                DbAssertions.row("SELECT project_id, object_key, created_by FROM rt.files WHERE id = ?", id);
        assertThat(row)
                .containsEntry("project_id", ApiTestKeys.PROJECT_A)
                .containsEntry("object_key", "rt/" + ApiTestKeys.TENANT + "/" + ApiTestKeys.PROJECT_A + "/files/" + id)
                .containsEntry("created_by", "key_test_a");
        assertThat(RuntimeTestObjectStore.get().get((String) row.get("object_key")))
                .isPresent();

        BinaryResponse content =
                api.binary("GET /v1/files/{file_id}/content", HttpMethod.GET, "/v1/files/" + id + "/content", null);
        assertThat(content.bytes()).isEqualTo(html.getBytes(StandardCharsets.UTF_8));
        assertThat(content.headers().getContentType().toString()).startsWith("text/html");
        assertThat(content.headers().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(content.headers().getContentDisposition().getType()).isEqualTo("attachment");
        assertThat(content.headers().getContentDisposition().getFilename()).isEqualTo("报表 .png");

        assertThat(api(ApiTestKeys.KEY_B)
                        .call("GET /v1/files/{file_id}", HttpMethod.GET, "/v1/files/" + id, null, null)
                        .text("code"))
                .as("其他项目的文件与不存在相同")
                .isEqualTo("file.not_found");
        assertThat(api(ApiTestKeys.KEY_B)
                        .binary("GET /v1/files/{file_id}/content", HttpMethod.GET, "/v1/files/" + id + "/content", null)
                        .status())
                .isEqualTo(404);
    }

    @Test
    void uploadsAreIdempotentLimitedAndRequireTheScope() {
        ApiTestClient api = api(ApiTestKeys.KEY_A);
        String key = "upload-" + System.nanoTime();
        byte[] content = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);

        ApiResponse first = api.uploadFile("data.csv", content, key);
        ApiResponse replayed = api.uploadFile("data.csv", content, key);
        assertThat(replayed.status()).isEqualTo(201);
        assertThat(replayed.text("id")).isEqualTo(first.text("id"));
        assertThat(replayed.headers().getFirst("Idempotent-Replayed")).isEqualTo("true");
        ApiResponse conflict = api.uploadFile("data.csv", "other".getBytes(StandardCharsets.UTF_8), key);
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.text("code")).isEqualTo("idempotency.key_reused");

        ApiResponse empty = api.uploadFile("empty.txt", new byte[0], null);
        assertThat(empty.status()).isEqualTo(400);
        assertThat(empty.body().get("errors").get(0).get("field").asString()).isEqualTo("file");

        ApiResponse tooLarge = api.uploadFile("big.bin", new byte[64 * 1024 + 1], null);
        assertThat(tooLarge.status()).isEqualTo(413);
        assertThat(tooLarge.text("code")).as("规范写的错误码（R2 审查 A-09）").isEqualTo("file.too_large");
        assertThat(DbAssertions.list("SELECT count(*) FROM rt.files WHERE filename = 'big.bin'", Long.class))
                .containsExactly(0L);

        ApiResponse forbidden = api(ApiTestKeys.KEY_A_READ_ONLY).uploadFile("x.txt", content, null);
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.text("code")).isEqualTo("auth.insufficient_scope");
    }
}
