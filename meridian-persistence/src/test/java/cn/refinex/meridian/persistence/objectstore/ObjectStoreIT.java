package cn.refinex.meridian.persistence.objectstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.testsupport.MeridianContainers;
import cn.refinex.meridian.testsupport.SpringContainerProperties;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** T02-A07：在 MinIO 上完成上传、下载、按前缀删除、预签名下载。 */
class ObjectStoreIT {

    private static ObjectStore store;

    @BeforeAll
    static void connect() {
        store = ObjectStore.create(new ObjectStoreProperties(
                URI.create(MeridianContainers.minio().getS3URL()),
                SpringContainerProperties.MINIO_REGION,
                MeridianContainers.MINIO_BUCKET,
                MeridianContainers.minio().getUserName(),
                MeridianContainers.minio().getPassword(),
                true));
    }

    @AfterAll
    static void close() {
        store.close();
    }

    @Test
    void uploadDownloadAndDelete() throws Exception {
        String prefix = "tenants/ten_A/" + Ids.newUlid() + "/";
        byte[] content = "{\"结果\":\"大工具结果\"}".getBytes(StandardCharsets.UTF_8);

        store.put(prefix + "result.json", content, "application/json");
        byte[] streamed = "流式上传".getBytes(StandardCharsets.UTF_8);
        store.put(prefix + "stream.txt", new ByteArrayInputStream(streamed), streamed.length, "text/plain");

        assertThat(store.get(prefix + "result.json")).hasValue(content);
        try (InputStream in = store.open(prefix + "stream.txt").orElseThrow()) {
            assertThat(in.readAllBytes()).isEqualTo(streamed);
        }
        assertThat(store.get(prefix + "missing")).isEmpty();
        assertThat(store.open(prefix + "missing")).isEmpty();

        store.delete(prefix + "result.json");
        store.delete(prefix + "result.json");
        assertThat(store.get(prefix + "result.json")).isEmpty();
    }

    @Test
    void deleteByPrefixRemovesOnlyMatchingObjects() {
        String base = "tenants/ten_A/" + Ids.newUlid() + "/";
        for (int i = 0; i < 5; i++) {
            store.put(base + "sessions/ses_1/artifact-" + i, new byte[] {(byte) i}, "application/octet-stream");
        }
        store.put(base + "sessions/ses_10/keep", new byte[] {1}, "application/octet-stream");

        assertThat(store.deleteByPrefix(base + "sessions/ses_1/")).isEqualTo(5);

        assertThat(store.get(base + "sessions/ses_1/artifact-0")).isEmpty();
        assertThat(store.get(base + "sessions/ses_10/keep")).isPresent();
        assertThat(store.deleteByPrefix(base + "sessions/ses_1/")).isZero();
    }

    @Test
    void blankPrefixIsRejected() {
        assertThatThrownBy(() -> store.deleteByPrefix(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void presignedUrlDownloadsWithoutCredentials() throws Exception {
        String key = "artifacts/" + Ids.newUlid() + "/report.txt";
        byte[] content = "预签名下载".getBytes(StandardCharsets.UTF_8);
        store.put(key, content, "text/plain");

        URI url = store.presignGet(key, Duration.ofMinutes(5)).toURI();

        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<byte[]> response =
                    http.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(content);

            URI unsigned = URI.create(url.toString().substring(0, url.toString().indexOf('?')));
            HttpResponse<Void> denied =
                    http.send(HttpRequest.newBuilder(unsigned).GET().build(), HttpResponse.BodyHandlers.discarding());
            assertThat(denied.statusCode()).isEqualTo(403);
        }
    }
}
